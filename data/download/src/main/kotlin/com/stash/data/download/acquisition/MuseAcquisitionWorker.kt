package com.stash.data.download.acquisition

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.data.download.search.SearchDownloadCoordinator
import com.stash.data.download.search.SearchDownloadStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last

@HiltWorker
class MuseAcquisitionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val prefs: MuseAcquisitionPreferences,
    private val client: MuseAcquisitionClient,
    private val matcher: MuseAcquisitionMatcher,
    private val downloadCoordinator: SearchDownloadCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = prefs.current()
        if (!config.configured) return Result.success()

        val jobs = try {
            client.listApproved(MAX_JOBS_PER_RUN)
        } catch (error: MuseAcquisitionRemoteException) {
            prefs.recordResult(MuseAcquisitionPreferences.RESULT_CONNECTION_FAILED, successful = false)
            return if (error.retryable) Result.retry() else Result.failure()
        }
        prefs.recordAttempt(jobs.size)

        for ((index, job) in jobs.withIndex()) {
            val match = runSafely { matcher.findConfidentMatch(job.query) }.getOrElse {
                prefs.recordResult(MuseAcquisitionPreferences.RESULT_CONNECTION_FAILED, successful = false)
                return Result.retry()
            }
            val claim = try {
                client.claim(job.id)
            } catch (error: MuseAcquisitionRemoteException) {
                if (error.code == "not_claimable") continue
                prefs.recordResult(MuseAcquisitionPreferences.RESULT_CONNECTION_FAILED, successful = false)
                return if (error.retryable) Result.retry() else Result.failure()
            }

            if (match == null) {
                val report = runSafely {
                    client.reportFailed(
                        jobId = job.id,
                        leaseToken = claim.leaseToken,
                        errorCode = "no_confident_match",
                        retryable = false,
                    )
                }
                if (report.isFailure) return Result.retry()
                prefs.recordResult(
                    MuseAcquisitionPreferences.RESULT_NO_CONFIDENT_MATCH,
                    successful = false,
                    pendingCount = jobs.size - index - 1,
                )
                continue
            }

            val reportedDownloading = runSafely {
                client.reportDownloading(job.id, claim.leaseToken)
            }
            if (reportedDownloading.isFailure) return Result.retry()

            val status = runSafely { downloadCoordinator.download(match).last() }.getOrElse {
                val reported = runSafely {
                    client.reportFailed(job.id, claim.leaseToken, "download_failed", retryable = true)
                }
                if (reported.isFailure) return Result.retry()
                prefs.recordResult(MuseAcquisitionPreferences.RESULT_DOWNLOAD_FAILED, successful = false)
                continue
            }

            val result = when (status) {
                SearchDownloadStatus.Completed -> complete(job, claim, match.videoId, jobs.size - index - 1)
                SearchDownloadStatus.WaitingForLossless -> fail(
                    job = job,
                    claim = claim,
                    errorCode = "lossless_unavailable",
                    preferenceResult = MuseAcquisitionPreferences.RESULT_WAITING_FOR_LOSSLESS,
                    pendingCount = jobs.size - index - 1,
                )
                is SearchDownloadStatus.Failed -> fail(
                    job = job,
                    claim = claim,
                    errorCode = "download_failed",
                    preferenceResult = MuseAcquisitionPreferences.RESULT_DOWNLOAD_FAILED,
                    pendingCount = jobs.size - index - 1,
                )
                SearchDownloadStatus.Resolving,
                is SearchDownloadStatus.Downloading -> false
            }
            if (!result) return Result.retry()
        }

        return Result.success()
    }

    private suspend fun complete(
        job: MuseAcquisitionJob,
        claim: MuseAcquisitionClaim,
        videoId: String,
        pendingCount: Int,
    ): Boolean {
        val reported = runSafely { client.reportCompleted(job.id, claim.leaseToken, videoId) }
        if (reported.isFailure) return false
        prefs.recordResult(
            MuseAcquisitionPreferences.RESULT_TRACK_DOWNLOADED,
            successful = true,
            pendingCount = pendingCount,
        )
        return true
    }

    private suspend fun fail(
        job: MuseAcquisitionJob,
        claim: MuseAcquisitionClaim,
        errorCode: String,
        preferenceResult: String,
        pendingCount: Int,
    ): Boolean {
        val reported = runSafely {
            client.reportFailed(job.id, claim.leaseToken, errorCode, retryable = true)
        }
        if (reported.isFailure) return false
        prefs.recordResult(preferenceResult, successful = false, pendingCount = pendingCount)
        return true
    }

    private companion object {
        const val MAX_JOBS_PER_RUN = 3
    }
}

private suspend fun <T> runSafely(block: suspend () -> T): kotlin.Result<T> = try {
    kotlin.Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    kotlin.Result.failure(error)
}
