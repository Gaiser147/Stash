package com.stash.data.download.acquisition

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.model.TrackItem
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.search.SearchDownloadCoordinator
import com.stash.data.download.search.SearchDownloadStatus
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackAcquisitionPipelineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private val registry: LosslessSourceRegistry = mockk()
    private val downloader: LosslessUrlDownloader = mockk()
    private val finalizer: TrackFinalizer = mockk()
    private val legacyCoordinator: SearchDownloadCoordinator = mockk()

    @Test
    fun `v2 preserves source headers and decryption then reports probed media and hash`() = runTest {
        every { context.cacheDir } returns temporaryFolder.root
        val format = AudioFormat("flac", 1_200, 96_000, 24)
        val source = SourceResult(
            sourceId = "amz",
            downloadUrl = "https://source.example.test/encrypted",
            downloadHeaders = mapOf("Authorization" to "Bearer source-only", "Referer" to "https://source.example.test"),
            format = format,
            confidence = 0.99f,
            decryptionKey = "00112233445566778899aabbccddeeff",
        )
        val committed = temporaryFolder.newFile("committed.flac").apply {
            writeText("actual finalized lossless bytes")
        }
        coEvery { registry.resolve(any(), false) } returns source
        coEvery {
            downloader.downloadBounded(source, any(), TrackAcquisitionPipeline.MAX_ARTIFACT_BYTES, any())
        } coAnswers {
            val destination = secondArg<File>().apply { writeText("decrypted bytes") }
            arg<(Long, Long) -> Unit>(3)(destination.length(), destination.length())
            Result.success(destination)
        }
        coEvery { finalizer.finalizeFile(any(), any(), format, true) } returns
            TrackFinalizer.FinalizeResult.Success(
                committed = FileOrganizer.CommittedTrack(committed.absolutePath, committed.length()),
                meta = AudioMetadata(
                    durationMs = 240_000L,
                    bitrateKbps = 1_050,
                    format = "flac",
                    bitsPerSample = 24,
                    sampleRateHz = 96_000,
                ),
            )
        val progress = mutableListOf<TrackAcquisitionProgress>()

        val outcome = pipeline().acquireV2(job(), track()) { progress += it }

        assertThat(outcome).isInstanceOf(TrackAcquisitionOutcome.ArtifactReady::class.java)
        val artifact = (outcome as TrackAcquisitionOutcome.ArtifactReady).artifact
        assertThat(artifact.uploadFile).isEqualTo(committed)
        assertThat(artifact.sha256).hasLength(64)
        assertThat(artifact.sizeBytes).isEqualTo(committed.length())
        assertThat(artifact.media).isEqualTo(MuseActualMedia("flac", 1_050, 96_000, 24, 240_000L))
        assertThat(progress.map { it.phase }).containsAtLeast(
            TrackAcquisitionPhase.RESOLVING,
            TrackAcquisitionPhase.DOWNLOADING,
            TrackAcquisitionPhase.VERIFYING,
        )
        coVerify(exactly = 1) {
            downloader.downloadBounded(source, any(), TrackAcquisitionPipeline.MAX_ARTIFACT_BYTES, any())
        }
    }

    @Test
    fun `v2 rejects resolver claims when committed media is actually lossy`() = runTest {
        every { context.cacheDir } returns temporaryFolder.root
        val source = SourceResult(
            sourceId = "test",
            downloadUrl = "https://source.example.test/file",
            format = AudioFormat("flac", 900),
            confidence = 1f,
        )
        val committed = temporaryFolder.newFile("fake.flac").apply { writeText("lossy data") }
        coEvery { registry.resolve(any(), false) } returns source
        coEvery { downloader.downloadBounded(source, any(), any(), any()) } coAnswers {
            Result.success(secondArg<File>().apply { writeText("bytes") })
        }
        coEvery { finalizer.finalizeFile(any(), any(), any(), true) } returns
            TrackFinalizer.FinalizeResult.Success(
                FileOrganizer.CommittedTrack(committed.absolutePath, committed.length()),
                AudioMetadata(240_000L, 192, "opus"),
            )

        assertThat(pipeline().acquireV2(job(), track()))
            .isEqualTo(TrackAcquisitionOutcome.Failed("invalid_media", retryable = false))
        assertThat(committed.exists()).isFalse()
    }

    @Test
    fun `v2 cancellation escapes instead of becoming source miss`() {
        every { context.cacheDir } returns temporaryFolder.root
        coEvery { registry.resolve(any(), false) } throws CancellationException("worker stopped")

        assertThrows(CancellationException::class.java) {
            runTest { pipeline().acquireV2(job(), track()) }
        }
        coVerify(exactly = 0) { downloader.downloadBounded(any(), any(), any(), any()) }
    }

    @Test
    fun `v1 compatibility remains isolated behind legacy coordinator`() = runTest {
        every { legacyCoordinator.download(any()) } returns flowOf(
            SearchDownloadStatus.Resolving,
            SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.LOSSLESS),
            SearchDownloadStatus.Completed,
        )

        assertThat(pipeline().acquireV1Compatibility(track()))
            .isEqualTo(TrackAcquisitionOutcome.LegacyCompleted("yt-id"))
        coVerify(exactly = 0) { registry.resolve(any(), any()) }
    }

    private fun pipeline() = TrackAcquisitionPipeline(
        context,
        registry,
        downloader,
        finalizer,
        legacyCoordinator,
    )

    private fun track() = TrackItem(
        videoId = "yt-id",
        title = "Teardrop",
        artist = "Massive Attack",
        durationSeconds = 240.0,
        thumbnailUrl = null,
    )

    private fun job() = MuseAcquisitionJob(
        id = "123e4567-e89b-12d3-a456-426614174000",
        query = "Massive Attack Teardrop",
        attempt = 0,
        approvedAt = "2026-07-14T10:00:00.000Z",
        expiresAt = "2026-07-21T10:00:00.000Z",
        artist = "Massive Attack",
        title = "Teardrop",
        durationMs = 240_000L,
    )
}
