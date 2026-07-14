package com.stash.data.download.acquisition

import android.content.Context
import androidx.core.net.toUri
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.search.SearchDownloadCoordinator
import com.stash.data.download.search.SearchDownloadStatus
import com.stash.data.download.shared.TrackFinalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The one Stash-side acquisition entry point.
 *
 * [acquireV2] is deliberately lossless-only and preserves the complete
 * [com.stash.data.download.lossless.SourceResult] when handing it to
 * [LosslessUrlDownloader]. That is load-bearing: source-specific request
 * headers and encrypted Amazon/CMAF results must not pass through the simpler
 * preview-cache copier. The returned artifact describes bytes read back from
 * the committed file, not claims made by the resolver.
 *
 * [acquireV1Compatibility] isolates the old inbox behaviour. A v1 server has
 * no upload ticket or ingest receipt, so its existing local-download-then-
 * complete contract cannot provide v2's upload-before-complete guarantee.
 */
@Singleton
class TrackAcquisitionPipeline @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: LosslessSourceRegistry,
    private val downloader: LosslessUrlDownloader,
    private val finalizer: TrackFinalizer,
    private val legacyCoordinator: SearchDownloadCoordinator,
) {
    suspend fun acquireV1Compatibility(
        track: TrackItem,
        onProgress: suspend (TrackAcquisitionProgress) -> Unit = {},
    ): TrackAcquisitionOutcome {
        var terminal: TrackAcquisitionOutcome = TrackAcquisitionOutcome.Failed(
            code = "download_failed",
            retryable = true,
        )
        legacyCoordinator.download(track).collect { status ->
            when (status) {
                SearchDownloadStatus.Resolving -> onProgress.phase(TrackAcquisitionPhase.RESOLVING)
                is SearchDownloadStatus.Downloading -> onProgress.phase(TrackAcquisitionPhase.DOWNLOADING)
                SearchDownloadStatus.Completed -> {
                    terminal = TrackAcquisitionOutcome.LegacyCompleted(track.videoId)
                }
                SearchDownloadStatus.WaitingForLossless -> {
                    terminal = TrackAcquisitionOutcome.Failed("lossless_unavailable", retryable = true)
                }
                is SearchDownloadStatus.Failed -> {
                    terminal = TrackAcquisitionOutcome.Failed("download_failed", retryable = true)
                }
            }
        }
        return terminal
    }

    suspend fun acquireV2(
        job: MuseAcquisitionJob,
        track: TrackItem,
        onProgress: suspend (TrackAcquisitionProgress) -> Unit = {},
    ): TrackAcquisitionOutcome = coroutineScope {
        // Phase boundaries use suspending send and must never be conflated
        // away. Byte updates are best-effort trySend into this bounded buffer.
        val events = Channel<TrackAcquisitionProgress>(Channel.BUFFERED)
        val reporter = launch {
            for (event in events) onProgress(event)
        }

        try {
            events.send(TrackAcquisitionProgress(TrackAcquisitionPhase.RESOLVING))
            val source = try {
                registry.resolve(
                    TrackQuery(
                        artist = job.artist ?: track.artist,
                        title = job.title ?: track.title,
                        album = job.album ?: track.album,
                        isrc = job.isrc,
                        durationMs = job.durationMs
                            ?: track.durationSeconds.takeIf { it > 0.0 }?.let { (it * 1_000).toLong() },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@coroutineScope TrackAcquisitionOutcome.Failed("resolve_failed", retryable = true)
            }

            if (source == null || source.confidence < MIN_CONFIDENCE || !source.format.isLossless) {
                return@coroutineScope TrackAcquisitionOutcome.Failed("lossless_unavailable", retryable = true)
            }

            val extension = source.format.codec.lowercase().takeIf { it.matches(SAFE_EXTENSION) } ?: "flac"
            val tempDir = File(context.cacheDir, "muse_acquisition").also { it.mkdirs() }
            val tempFile = File(tempDir, "${job.id}.$extension")
            runCatching { tempFile.delete() }

            events.send(TrackAcquisitionProgress(TrackAcquisitionPhase.DOWNLOADING))
            val fetched = downloader.downloadBounded(
                source = source,
                destination = tempFile,
                maxBytes = MAX_ARTIFACT_BYTES,
                onProgress = { bytesRead, totalBytes ->
                    events.trySend(
                        TrackAcquisitionProgress(
                            phase = TrackAcquisitionPhase.DOWNLOADING,
                            bytesProcessed = bytesRead,
                            totalBytes = totalBytes.takeIf { it > 0 },
                        ),
                    )
                },
            )
            if (fetched.isFailure) {
                runCatching { tempFile.delete() }
                return@coroutineScope TrackAcquisitionOutcome.Failed("download_failed", retryable = true)
            }

            events.send(TrackAcquisitionProgress(TrackAcquisitionPhase.VERIFYING))
            val finalized = finalizer.finalizeFile(
                sourceFile = tempFile,
                track = track.toDomainTrack(job),
                format = source.format,
            )
            if (finalized !is TrackFinalizer.FinalizeResult.Success) {
                runCatching { tempFile.delete() }
                return@coroutineScope TrackAcquisitionOutcome.Failed("finalize_failed", retryable = true)
            }

            val meta = finalized.meta
                ?: return@coroutineScope invalidCommittedArtifact(finalized.committed.filePath, "invalid_media")
            if (!meta.isVerifiedLossless()) {
                return@coroutineScope invalidCommittedArtifact(finalized.committed.filePath, "invalid_media")
            }
            if (!durationMatches(job.durationMs, track.durationSeconds, meta.durationMs)) {
                return@coroutineScope invalidCommittedArtifact(finalized.committed.filePath, "duration_mismatch")
            }

            val uploadFile = materializeForUpload(finalized.committed.filePath, job.id, extension)
                ?: return@coroutineScope TrackAcquisitionOutcome.Failed("artifact_unreadable", retryable = true)
            val deleteAfterUpload = finalized.committed.filePath.startsWith("content://")
            if (uploadFile.length() <= 0L || uploadFile.length() > MAX_ARTIFACT_BYTES) {
                if (deleteAfterUpload) runCatching { uploadFile.delete() }
                return@coroutineScope TrackAcquisitionOutcome.Failed("invalid_file_size", retryable = false)
            }

            val sha256 = sha256(uploadFile) { bytes ->
                events.trySend(
                    TrackAcquisitionProgress(
                        phase = TrackAcquisitionPhase.VERIFYING,
                        bytesProcessed = bytes,
                        totalBytes = uploadFile.length(),
                    ),
                )
            }
            TrackAcquisitionOutcome.ArtifactReady(
                AcquiredTrackArtifact(
                    localTrackId = sha256.take(32),
                    committedPath = finalized.committed.filePath,
                    uploadFile = uploadFile,
                    deleteUploadFileAfterUse = deleteAfterUpload,
                    sha256 = sha256,
                    sizeBytes = uploadFile.length(),
                    media = MuseActualMedia(
                        codec = meta.format.lowercase(),
                        bitrateKbps = meta.bitrateKbps.coerceAtLeast(0),
                        sampleRateHz = meta.sampleRateHz,
                        bitsPerSample = meta.bitsPerSample,
                        durationMs = meta.durationMs,
                    ),
                ),
            )
        } finally {
            events.close()
            reporter.join()
        }
    }

    private fun materializeForUpload(path: String, jobId: String, extension: String): File? {
        if (!path.startsWith("content://")) return File(path).takeIf(File::isFile)
        return runCatching {
            val copy = File(context.cacheDir, "muse_upload_${jobId}.$extension")
            context.contentResolver.openInputStream(path.toUri())?.use { input ->
                copy.outputStream().use { output -> input.copyTo(output) }
            } ?: error("committed content URI is unreadable")
            copy
        }.getOrNull()
    }

    private fun invalidCommittedArtifact(path: String, code: String): TrackAcquisitionOutcome.Failed {
        runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(path.toUri(), null, null)
            } else {
                File(path).delete()
            }
        }
        return TrackAcquisitionOutcome.Failed(code, retryable = false)
    }

    private suspend fun sha256(file: File, onProgress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                currentCoroutineContext().ensureActive()
                digest.update(buffer, 0, count)
                total += count
                onProgress(total)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun AudioMetadata.isVerifiedLossless(): Boolean =
        format.lowercase() in AudioFormat.LOSSLESS_CODECS && durationMs > 0L

    private fun durationMatches(expectedMs: Long?, fallbackSeconds: Double, actualMs: Long): Boolean {
        val expected = expectedMs ?: fallbackSeconds.takeIf { it > 0.0 }?.let { (it * 1_000).toLong() }
        expected ?: return true
        return kotlin.math.abs(expected - actualMs) <= maxOf(MIN_DURATION_TOLERANCE_MS, expected / 20)
    }

    private fun TrackItem.toDomainTrack(job: MuseAcquisitionJob) = Track(
        title = job.title ?: title,
        artist = job.artist ?: artist,
        album = job.album ?: album.orEmpty(),
        albumArtist = albumArtist.orEmpty(),
        durationMs = job.durationMs ?: (durationSeconds * 1_000).toLong(),
        albumArtUrl = thumbnailUrl,
        youtubeId = videoId,
        isrc = job.isrc,
    )

    private suspend fun (suspend (TrackAcquisitionProgress) -> Unit).phase(phase: TrackAcquisitionPhase) {
        invoke(TrackAcquisitionProgress(phase))
    }

    companion object {
        const val MAX_ARTIFACT_BYTES = 500L * 1024L * 1024L
        private const val MIN_CONFIDENCE = 0.65f
        private const val MIN_DURATION_TOLERANCE_MS = 5_000L
        private val SAFE_EXTENSION = Regex("[a-z0-9]{1,8}")
    }
}

enum class TrackAcquisitionPhase(val wireName: String) {
    RESOLVING("resolving"),
    DOWNLOADING("downloading"),
    VERIFYING("verifying"),
    UPLOADING("uploading"),
    VALIDATING("validating"),
}

data class TrackAcquisitionProgress(
    val phase: TrackAcquisitionPhase,
    val bytesProcessed: Long? = null,
    val totalBytes: Long? = null,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { total ->
            (((bytesProcessed ?: 0L).coerceIn(0L, total) * 100L) / total).toInt()
        }
}

sealed interface TrackAcquisitionOutcome {
    data class ArtifactReady(val artifact: AcquiredTrackArtifact) : TrackAcquisitionOutcome
    data class LegacyCompleted(val localTrackId: String) : TrackAcquisitionOutcome
    data class Failed(val code: String, val retryable: Boolean) : TrackAcquisitionOutcome
}

data class AcquiredTrackArtifact(
    val localTrackId: String,
    val committedPath: String,
    val uploadFile: File,
    val deleteUploadFileAfterUse: Boolean,
    val sha256: String,
    val sizeBytes: Long,
    val media: MuseActualMedia,
)
