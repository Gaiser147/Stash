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
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val relativePath = inputData.getString(KEY_RELATIVE_PATH) ?: return Result.failure()
        return when (ingestClient.uploadFile(filePath, relativePath)) {
            NavidromeUploadOutcome.Success -> Result.success()
            NavidromeUploadOutcome.PermanentFailure -> Result.failure()
            NavidromeUploadOutcome.RetryableFailure -> Result.retry()
        }
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val KEY_RELATIVE_PATH = "relative_path"
    }
}
