package com.stash.data.download.acquisition

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.model.TrackItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltWorker
class MuseAcquisitionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val prefs: MuseAcquisitionPreferences,
    private val client: MuseAcquisitionClient,
    private val matcher: MuseAcquisitionMatcher,
    private val pipeline: TrackAcquisitionPipeline,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = prefs.current()
        if (!config.configured) return Result.success()

        val contract = try {
            client.negotiateContract()
        } catch (error: MuseAcquisitionRemoteException) {
            prefs.recordResult(MuseAcquisitionPreferences.RESULT_CONNECTION_FAILED, successful = false)
            return if (error.retryable) Result.retry() else Result.failure()
        }
        return when (contract) {
            MuseAcquisitionContract.V1 -> runV1Compatibility()
            MuseAcquisitionContract.V2 -> runV2()
        }
    }

    /** Existing behaviour, intentionally isolated from receipt-gated v2. */
    private suspend fun runV1Compatibility(): Result {
        val jobs = try {
            client.listApproved(MAX_V1_JOBS_PER_RUN)
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
                if (!reportV1Failure(job, claim, "no_confident_match", retryable = false)) return Result.retry()
                prefs.recordResult(
                    MuseAcquisitionPreferences.RESULT_NO_CONFIDENT_MATCH,
                    successful = false,
                    pendingCount = jobs.size - index - 1,
                )
                continue
            }

            if (runSafely { client.reportDownloading(job.id, claim.leaseToken) }.isFailure) return Result.retry()
            when (val outcome = runSafely { pipeline.acquireV1Compatibility(match) }.getOrElse {
                TrackAcquisitionOutcome.Failed("download_failed", retryable = true)
            }) {
                is TrackAcquisitionOutcome.LegacyCompleted -> {
                    if (runSafely {
                            client.reportCompleted(job.id, claim.leaseToken, outcome.localTrackId)
                        }.isFailure
                    ) return Result.retry()
                    prefs.recordResult(
                        MuseAcquisitionPreferences.RESULT_TRACK_DOWNLOADED,
                        successful = true,
                        pendingCount = jobs.size - index - 1,
                    )
                }
                is TrackAcquisitionOutcome.Failed -> {
                    if (!reportV1Failure(job, claim, outcome.code, outcome.retryable)) return Result.retry()
                    prefs.recordResult(
                        if (outcome.code == "lossless_unavailable") {
                            MuseAcquisitionPreferences.RESULT_WAITING_FOR_LOSSLESS
                        } else {
                            MuseAcquisitionPreferences.RESULT_DOWNLOAD_FAILED
                        },
                        successful = false,
                        pendingCount = jobs.size - index - 1,
                    )
                }
                is TrackAcquisitionOutcome.ArtifactReady -> error("v1 pipeline returned a v2 artifact")
            }
        }
        return Result.success()
    }

    private suspend fun runV2(): Result {
        val claim = try {
            client.claimAssigned()
        } catch (error: MuseAcquisitionRemoteException) {
            prefs.recordResult(MuseAcquisitionPreferences.RESULT_CONNECTION_FAILED, successful = false)
            return if (error.retryable) Result.retry() else Result.failure()
        }
        prefs.recordAttempt(if (claim == null) 0 else 1)
        claim ?: return Result.success()

        val job = claim.job
        val match = job.structuredTrack() ?: runSafely { matcher.findConfidentMatch(job.query) }.getOrElse {
            return failV2(claim, "match_failed", retryable = true)
        }
        if (match == null) return failV2(claim, "no_confident_match", retryable = false)

        return withLeaseHeartbeat(claim) { phaseState ->
            val throttler = PhaseThrottler()
            val outcome = try {
                pipeline.acquireV2(job, match) { progress ->
                    phaseState.value = progress.phase
                    if (throttler.shouldReport(progress)) {
                        client.reportPhase(job.id, claim.leaseToken, progress)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: MuseAcquisitionRemoteException) {
                return@withLeaseHeartbeat if (error.retryable) Result.retry() else Result.failure()
            } catch (_: Exception) {
                return@withLeaseHeartbeat failV2(claim, "acquisition_failed", retryable = true)
            }

            when (outcome) {
                is TrackAcquisitionOutcome.Failed -> failV2(claim, outcome.code, outcome.retryable)
                is TrackAcquisitionOutcome.LegacyCompleted -> Result.failure()
                is TrackAcquisitionOutcome.ArtifactReady -> uploadAndComplete(claim, outcome.artifact, phaseState)
            }
        }
    }

    private suspend fun uploadAndComplete(
        claim: MuseAcquisitionClaim,
        artifact: AcquiredTrackArtifact,
        phaseState: MutablePhase,
    ): Result {
        val descriptor = MuseArtifactDescriptor(
            localTrackId = artifact.localTrackId,
            sha256 = artifact.sha256,
            sizeBytes = artifact.sizeBytes,
            media = artifact.media,
        )
        try {
            val ticket = client.requestUploadTicket(claim.job.id, claim.leaseToken, descriptor)
            phaseState.value = TrackAcquisitionPhase.UPLOADING
            client.reportPhase(
                claim.job.id,
                claim.leaseToken,
                TrackAcquisitionProgress(TrackAcquisitionPhase.UPLOADING, 0L, artifact.sizeBytes),
            )
            val receipt = client.uploadArtifact(ticket, artifact.uploadFile, artifact.sha256)
            phaseState.value = TrackAcquisitionPhase.VALIDATING
            client.reportPhase(
                claim.job.id,
                claim.leaseToken,
                TrackAcquisitionProgress(
                    TrackAcquisitionPhase.VALIDATING,
                    artifact.sizeBytes,
                    artifact.sizeBytes,
                ),
            )
            // This is the only v2 completion call. It is unreachable without
            // a server-issued ingest receipt from the successful upload.
            client.completeV2(claim.job.id, claim.leaseToken, receipt, descriptor)
            prefs.recordResult(
                MuseAcquisitionPreferences.RESULT_TRACK_DOWNLOADED,
                successful = true,
                pendingCount = 0,
            )
            return Result.success()
        } catch (error: MuseAcquisitionRemoteException) {
            runSafely {
                client.failV2(claim.job.id, claim.leaseToken, error.code, error.retryable)
            }
            prefs.recordResult(MuseAcquisitionPreferences.RESULT_DOWNLOAD_FAILED, successful = false)
            return if (error.retryable) Result.retry() else Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runSafely {
                client.failV2(claim.job.id, claim.leaseToken, "upload_failed", retryable = true)
            }
            prefs.recordResult(MuseAcquisitionPreferences.RESULT_DOWNLOAD_FAILED, successful = false)
            return Result.retry()
        } finally {
            if (artifact.deleteUploadFileAfterUse) runCatching { artifact.uploadFile.delete() }
        }
    }

    private suspend fun failV2(
        claim: MuseAcquisitionClaim,
        code: String,
        retryable: Boolean,
    ): Result {
        val report = runSafely { client.failV2(claim.job.id, claim.leaseToken, code, retryable) }
        prefs.recordResult(
            if (code == "lossless_unavailable") {
                MuseAcquisitionPreferences.RESULT_WAITING_FOR_LOSSLESS
            } else {
                MuseAcquisitionPreferences.RESULT_DOWNLOAD_FAILED
            },
            successful = false,
        )
        if (report.isFailure) return Result.retry()
        return Result.success()
    }

    private suspend fun reportV1Failure(
        job: MuseAcquisitionJob,
        claim: MuseAcquisitionClaim,
        code: String,
        retryable: Boolean,
    ): Boolean = runSafely {
        client.reportFailed(job.id, claim.leaseToken, code, retryable)
    }.isSuccess

    private suspend fun <T> withLeaseHeartbeat(
        claim: MuseAcquisitionClaim,
        block: suspend (MutablePhase) -> T,
    ): T = coroutineScope {
        val phase = MutablePhase(TrackAcquisitionPhase.RESOLVING)
        val heartbeat = launch {
            while (currentCoroutineContext().isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                // Phase/report calls are the primary lease extension. A
                // transient heartbeat failure is retried on the next tick;
                // the next state-changing call remains authoritative.
                runSafely { client.heartbeat(claim.job.id, claim.leaseToken, phase.value) }
            }
        }
        try {
            block(phase)
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private fun MuseAcquisitionJob.structuredTrack(): TrackItem? {
        val structuredArtist = artist ?: return null
        val structuredTitle = title ?: return null
        return TrackItem(
            videoId = id,
            title = structuredTitle,
            artist = structuredArtist,
            durationSeconds = durationMs?.div(1_000.0) ?: 0.0,
            thumbnailUrl = null,
            album = album,
        )
    }

    private class MutablePhase(initial: TrackAcquisitionPhase) {
        @Volatile var value: TrackAcquisitionPhase = initial
    }

    private class PhaseThrottler {
        private var lastPhase: TrackAcquisitionPhase? = null
        private var lastPercent: Int = -1
        private var lastAt: Long = 0L

        fun shouldReport(progress: TrackAcquisitionProgress): Boolean {
            val now = System.currentTimeMillis()
            val percent = progress.percent ?: -1
            val should = progress.phase != lastPhase ||
                (percent >= 0 && percent >= lastPercent + MIN_PROGRESS_STEP) ||
                now - lastAt >= MAX_PROGRESS_SILENCE_MS
            if (should) {
                lastPhase = progress.phase
                lastPercent = percent
                lastAt = now
            }
            return should
        }
    }

    private companion object {
        const val MAX_V1_JOBS_PER_RUN = 3
        const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1_000L
        const val MIN_PROGRESS_STEP = 5
        const val MAX_PROGRESS_SILENCE_MS = 15_000L
    }
}

private suspend fun <T> runSafely(block: suspend () -> T): kotlin.Result<T> = try {
    kotlin.Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    kotlin.Result.failure(error)
}
