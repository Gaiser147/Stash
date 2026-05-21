package com.stash.data.download.export

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class NavidromeUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val ingestClient: NavidromeIngestClient,
    private val scheduler: NavidromeUploadScheduler,
    private val coverResolver: NavidromeCoverResolver,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val relativePath = inputData.getString(KEY_RELATIVE_PATH) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val artist = inputData.getString(KEY_ARTIST).orEmpty()
        val album = inputData.getString(KEY_ALBUM)
        val albumArtist = inputData.getString(KEY_ALBUM_ARTIST)
        val albumArtUrl = inputData.getString(KEY_ALBUM_ART_URL)
        val albumArtPath = inputData.getString(KEY_ALBUM_ART_PATH)
        val youtubeId = inputData.getString(KEY_YOUTUBE_ID)
        val artSource = coverResolver.resolve(
            artist = artist,
            title = title,
            albumArtPath = albumArtPath,
            albumArtUrl = albumArtUrl,
            youtubeId = youtubeId,
        )
        val metadata = if (title.isNotBlank() && artist.isNotBlank()) {
            NavidromeTrackMetadata(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist ?: artist,
            )
        } else {
            null
        }

        val fileOutcome = ingestClient.uploadFile(filePath, relativePath, metadata)
        if (fileOutcome != NavidromeUploadOutcome.Success) {
            return resultFor(fileOutcome)
        }
        val coverOutcome = if (artist.isNotBlank()) {
            ingestClient.uploadCover(
                artSource,
                scheduler.albumCoverRelativePath(artist, album, artSource),
            )
        } else {
            NavidromeUploadOutcome.Success
        }
        return resultFor(coverOutcome)
    }

    private fun resultFor(outcome: NavidromeUploadOutcome): Result =
        when (outcome) {
            NavidromeUploadOutcome.Success -> Result.success()
            NavidromeUploadOutcome.SkippedNoSource -> Result.success()
            NavidromeUploadOutcome.PermanentFailure -> Result.failure()
            NavidromeUploadOutcome.RetryableFailure -> Result.retry()
        }

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val KEY_RELATIVE_PATH = "relative_path"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_ALBUM_ARTIST = "album_artist"
        const val KEY_ALBUM_ART_URL = "album_art_url"
        const val KEY_ALBUM_ART_PATH = "album_art_path"
        const val KEY_YOUTUBE_ID = "youtube_id"
    }
}
