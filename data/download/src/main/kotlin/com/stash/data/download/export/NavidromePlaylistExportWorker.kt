package com.stash.data.download.export

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Locale

@HiltWorker
class NavidromePlaylistExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val prefs: NavidromeExportPreferences,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val ingestClient: NavidromeIngestClient,
    private val scheduler: NavidromeUploadScheduler,
    private val coverResolver: NavidromeCoverResolver,
    private val runtimeConstraints: NavidromeRuntimeConstraints,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = prefs.current()
        if (!config.configured) return Result.success()
        if (!runtimeConstraints.areSatisfied(config)) return Result.retry()
        prefs.recordAttempt()
        val fullExport = inputData.getBoolean(KEY_FULL_EXPORT, false)
        val stats = ExportStats()
        var retryableFailure = false

        if (fullExport) {
            retryableFailure = exportDownloadedTracks(trackDao.getAllDownloaded(), stats)
        }
        if (exportPlaylistManifests(stats)) retryableFailure = true

        when (ingestClient.syncComplete(stats.toSummary(if (fullExport) "full" else "playlists"))) {
            NavidromeUploadOutcome.Success,
            NavidromeUploadOutcome.SkippedNoSource -> Unit
            NavidromeUploadOutcome.PermanentFailure -> stats.playlistFailures++
            NavidromeUploadOutcome.RetryableFailure -> retryableFailure = true
        }

        val successful = !retryableFailure &&
            stats.trackFailures == 0 &&
            stats.coverFailures == 0 &&
            stats.playlistFailures == 0
        prefs.recordResult(
            result = if (successful) {
                if (fullExport) "full_export_complete" else "playlist_export_complete"
            } else {
                "export_incomplete"
            },
            successful = successful,
        )
        return if (retryableFailure) Result.retry() else Result.success()
    }

    private suspend fun exportDownloadedTracks(tracks: List<TrackEntity>, stats: ExportStats): Boolean {
        var retryableFailure = false
        val uploadedCovers = mutableSetOf<String>()
        tracks.filter { it.isDownloaded && !it.filePath.isNullOrBlank() }.forEach { track ->
            val filePath = track.filePath ?: return@forEach
            val album = track.album.takeIf(String::isNotBlank)
            val relativePath = scheduler.relativePathForTrack(
                track.artist,
                album,
                track.title,
                scheduler.extensionOfPath(filePath),
            )
            val metadata = NavidromeTrackMetadata(
                title = track.title,
                artist = track.artist,
                album = album,
                albumArtist = track.albumArtist.takeIf(String::isNotBlank) ?: track.artist,
            )
            when (ingestClient.uploadFile(filePath, relativePath, metadata)) {
                NavidromeUploadOutcome.Success -> stats.tracksUploaded++
                NavidromeUploadOutcome.SkippedNoSource -> stats.tracksSkipped++
                NavidromeUploadOutcome.PermanentFailure -> stats.trackFailures++
                NavidromeUploadOutcome.RetryableFailure -> {
                    stats.trackFailures++
                    retryableFailure = true
                }
            }

            val artSource = coverResolver.resolve(track)
            val coverPath = scheduler.albumCoverRelativePath(track.artist, album, artSource)
            if (uploadedCovers.add(coverPath)) {
                when (ingestClient.uploadCover(artSource, coverPath)) {
                    NavidromeUploadOutcome.Success -> stats.coversUploaded++
                    NavidromeUploadOutcome.SkippedNoSource -> stats.coversSkipped++
                    NavidromeUploadOutcome.PermanentFailure -> stats.coverFailures++
                    NavidromeUploadOutcome.RetryableFailure -> {
                        stats.coverFailures++
                        retryableFailure = true
                    }
                }
            }
        }
        return retryableFailure
    }

    private suspend fun exportPlaylistManifests(stats: ExportStats): Boolean {
        var retryableFailure = false
        listOf(MusicSource.SPOTIFY, MusicSource.YOUTUBE).forEach { source ->
            playlistDao.getSyncEnabledPlaylists(source).forEach { playlist ->
                val withTracks = playlistDao.getPlaylistWithTracks(playlist.id) ?: return@forEach
                val entries = withTracks.tracks
                    .filter { it.isDownloaded && !it.filePath.isNullOrBlank() }
                    .map { track ->
                        val filePath = requireNotNull(track.filePath)
                        PlaylistEntry(
                            track,
                            scheduler.navidromeLibraryEntry(
                                track.artist,
                                track.album.takeIf(String::isNotBlank),
                                track.title,
                                scheduler.extensionOfPath(filePath),
                            ),
                        )
                    }
                when (ingestClient.uploadPlaylist(playlistFileName(source, playlist.id, playlist.name), buildM3u(entries))) {
                    NavidromeUploadOutcome.Success -> stats.playlistsUploaded++
                    NavidromeUploadOutcome.SkippedNoSource,
                    NavidromeUploadOutcome.PermanentFailure -> stats.playlistFailures++
                    NavidromeUploadOutcome.RetryableFailure -> {
                        stats.playlistFailures++
                        retryableFailure = true
                    }
                }
            }
        }
        return retryableFailure
    }

    private fun buildM3u(entries: List<PlaylistEntry>): ByteArray = buildString {
        appendLine("#EXTM3U")
        entries.forEach { entry ->
            val durationSeconds = (entry.track.durationMs / 1000).coerceAtLeast(0)
            appendLine("#EXTINF:$durationSeconds,${m3uText(entry.track.artist)} - ${m3uText(entry.track.title)}")
            appendLine(entry.navidromePath)
        }
    }.toByteArray(Charsets.UTF_8)

    private fun playlistFileName(source: MusicSource, playlistId: Long, name: String): String {
        val readable = name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(80)
            .ifBlank { "playlist" }
        return "${source.name.lowercase(Locale.US)}-$playlistId-$readable.m3u8"
    }

    private fun m3uText(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()

    private data class PlaylistEntry(val track: TrackEntity, val navidromePath: String)

    private data class ExportStats(
        var tracksUploaded: Int = 0,
        var tracksSkipped: Int = 0,
        var trackFailures: Int = 0,
        var coversUploaded: Int = 0,
        var coversSkipped: Int = 0,
        var coverFailures: Int = 0,
        var playlistsUploaded: Int = 0,
        var playlistFailures: Int = 0,
    ) {
        fun toSummary(mode: String) = NavidromeSyncSummary(
            mode = mode,
            tracksUploaded = tracksUploaded,
            tracksSkipped = tracksSkipped,
            trackFailures = trackFailures,
            coversUploaded = coversUploaded,
            coversSkipped = coversSkipped,
            coverFailures = coverFailures,
            playlistsUploaded = playlistsUploaded,
            playlistFailures = playlistFailures,
        )
    }

    companion object {
        const val KEY_FULL_EXPORT = "full_export"
    }
}
