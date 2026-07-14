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
    private val prefs: NavidromeExportPreferences,
    private val ingestClient: NavidromeIngestClient,
    private val scheduler: NavidromeUploadScheduler,
    private val coverResolver: NavidromeCoverResolver,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!prefs.current().configured) return Result.success()
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val relativePath = inputData.getString(KEY_RELATIVE_PATH) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val artist = inputData.getString(KEY_ARTIST).orEmpty()
        val album = inputData.getString(KEY_ALBUM)
        val albumArtist = inputData.getString(KEY_ALBUM_ARTIST)
        val artSource = coverResolver.resolve(
            artist = artist,
            title = title,
            albumArtPath = inputData.getString(KEY_ALBUM_ART_PATH),
            albumArtUrl = inputData.getString(KEY_ALBUM_ART_URL),
            youtubeId = inputData.getString(KEY_YOUTUBE_ID),
        )
        val metadata = if (title.isNotBlank() && artist.isNotBlank()) {
            NavidromeTrackMetadata(title, artist, album, albumArtist ?: artist)
        } else {
            null
        }

        prefs.recordAttempt()
        val fileOutcome = ingestClient.uploadFile(filePath, relativePath, metadata)
        if (fileOutcome != NavidromeUploadOutcome.Success) {
            return finish(fileOutcome, "track_upload_failed")
        }
        val coverOutcome = if (artist.isNotBlank()) {
            ingestClient.uploadCover(
                artSource,
                scheduler.albumCoverRelativePath(artist, album, artSource),
            )
        } else {
            NavidromeUploadOutcome.SkippedNoSource
        }
        return finish(coverOutcome, "cover_upload_failed")
    }

    private suspend fun finish(outcome: NavidromeUploadOutcome, failureCode: String): Result = when (outcome) {
        NavidromeUploadOutcome.Success,
        NavidromeUploadOutcome.SkippedNoSource -> {
            prefs.recordResult("track_uploaded", successful = true)
            Result.success()
        }
        NavidromeUploadOutcome.PermanentFailure -> {
            prefs.recordResult(failureCode, successful = false)
            Result.failure()
        }
        NavidromeUploadOutcome.RetryableFailure -> {
            prefs.recordResult(failureCode, successful = false)
            Result.retry()
        }
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
