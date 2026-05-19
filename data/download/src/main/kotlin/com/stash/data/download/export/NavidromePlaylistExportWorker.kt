package com.stash.data.download.export

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.PlaylistDao
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
    private val scheduler: NavidromeUploadScheduler,
    private val ingestClient: NavidromeIngestClient,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!prefs.current().configured) return Result.success()

        var sawRetryableFailure = false
        val sources = listOf(MusicSource.SPOTIFY, MusicSource.YOUTUBE)

        sources.forEach { source ->
            playlistDao.getSyncEnabledPlaylists(source).forEach { playlist ->
                val withTracks = playlistDao.getPlaylistWithTracks(playlist.id) ?: return@forEach
                val entries = mutableListOf<PlaylistEntry>()

                withTracks.tracks
                    .filter { it.isDownloaded && !it.filePath.isNullOrBlank() }
                    .forEach { track ->
                        val filePath = track.filePath ?: return@forEach
                        val ext = scheduler.extensionOfPath(filePath)
                        val relativePath = scheduler.relativePathForTrack(
                            artist = track.artist,
                            album = track.album.takeIf { it.isNotBlank() },
                            title = track.title,
                            ext = ext,
                        )
                        when (ingestClient.uploadFile(filePath, relativePath)) {
                            NavidromeUploadOutcome.Success -> entries += PlaylistEntry(
                                track = track,
                                navidromePath = scheduler.navidromeLibraryEntry(
                                    artist = track.artist,
                                    album = track.album.takeIf { it.isNotBlank() },
                                    title = track.title,
                                    ext = ext,
                                ),
                            )
                            NavidromeUploadOutcome.PermanentFailure -> {
                                Log.w(TAG, "Skipping permanent Navidrome upload failure for track ${track.id}")
                            }
                            NavidromeUploadOutcome.RetryableFailure -> sawRetryableFailure = true
                        }
                    }

                val fileName = playlistFileName(source, playlist.id, playlist.name)
                when (ingestClient.uploadPlaylist(fileName, buildM3u(entries))) {
                    NavidromeUploadOutcome.Success -> Unit
                    NavidromeUploadOutcome.PermanentFailure -> {
                        Log.w(TAG, "Permanent Navidrome playlist upload failure for ${playlist.name}")
                    }
                    NavidromeUploadOutcome.RetryableFailure -> sawRetryableFailure = true
                }
            }
        }

        return if (sawRetryableFailure) Result.retry() else Result.success()
    }

    private fun buildM3u(entries: List<PlaylistEntry>): ByteArray {
        val body = buildString {
            appendLine("#EXTM3U")
            entries.forEach { entry ->
                val durationSeconds = (entry.track.durationMs / 1000).coerceAtLeast(0)
                appendLine("#EXTINF:$durationSeconds,${m3uText(entry.track.artist)} - ${m3uText(entry.track.title)}")
                appendLine(entry.navidromePath)
            }
        }
        return body.toByteArray(Charsets.UTF_8)
    }

    private fun playlistFileName(source: MusicSource, playlistId: Long, name: String): String =
        "${source.name.lowercase(Locale.US)}-$playlistId-${slugify(name).ifBlank { "playlist" }}.m3u8"

    private fun slugify(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(80)

    private fun m3uText(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()

    private data class PlaylistEntry(
        val track: TrackEntity,
        val navidromePath: String,
    )

    companion object {
        private const val TAG = "NavidromePlaylistExport"
    }
}
