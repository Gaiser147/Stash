package com.stash.data.download.acquisition

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.coVerifyOrder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MuseAcquisitionWorkerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context = mockk(relaxed = true)
    private val params: WorkerParameters = mockk(relaxed = true)
    private val prefs: MuseAcquisitionPreferences = mockk(relaxed = true)
    private val client: MuseAcquisitionClient = mockk()
    private val matcher: MuseAcquisitionMatcher = mockk()
    private val pipeline: TrackAcquisitionPipeline = mockk()

    @Test
    fun `confident request is leased downloaded and completed`() = runTest {
        val job = job()
        val track = TrackItem("yt-id", "Teardrop", "Massive Attack", 240.0, null)
        coEvery { prefs.current() } returns config()
        coEvery { client.negotiateContract() } returns MuseAcquisitionContract.V1
        coEvery { client.listApproved(3) } returns listOf(job)
        coEvery { matcher.findConfidentMatch(job.query) } returns track
        coEvery { client.claim(job.id) } returns claim(job)
        coEvery { client.reportDownloading(job.id, LEASE_TOKEN) } returns Unit
        coEvery { pipeline.acquireV1Compatibility(track, any()) } returns
            TrackAcquisitionOutcome.LegacyCompleted("yt-id")
        coEvery { client.reportCompleted(job.id, LEASE_TOKEN, "yt-id") } returns Unit

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { client.reportDownloading(job.id, LEASE_TOKEN) }
        coVerify(exactly = 1) { client.reportCompleted(job.id, LEASE_TOKEN, "yt-id") }
        coVerify(exactly = 0) { client.reportFailed(any(), any(), any(), any()) }
    }

    @Test
    fun `ambiguous request is failed without invoking downloader`() = runTest {
        val job = job()
        coEvery { prefs.current() } returns config()
        coEvery { client.negotiateContract() } returns MuseAcquisitionContract.V1
        coEvery { client.listApproved(3) } returns listOf(job)
        coEvery { matcher.findConfidentMatch(job.query) } returns null
        coEvery { client.claim(job.id) } returns claim(job)
        coEvery { client.reportFailed(job.id, LEASE_TOKEN, "no_confident_match", false) } returns Unit

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { client.reportFailed(job.id, LEASE_TOKEN, "no_confident_match", false) }
        coVerify(exactly = 0) { pipeline.acquireV1Compatibility(any(), any()) }
    }

    @Test
    fun `worker cancellation is never converted into a retry`() {
        val job = job()
        coEvery { prefs.current() } returns config()
        coEvery { client.negotiateContract() } returns MuseAcquisitionContract.V1
        coEvery { client.listApproved(3) } returns listOf(job)
        coEvery { matcher.findConfidentMatch(job.query) } throws CancellationException("stopped")

        assertThrows(CancellationException::class.java) { runTest { worker().doWork() } }
        coVerify(exactly = 0) { client.claim(any()) }
    }

    @Test
    fun `v2 completes only after artifact upload returned an ingest receipt`() = runTest {
        val file = temporaryFolder.newFile("track.flac").apply { writeText("verified flac bytes") }
        val job = job().copy(
            artist = "Massive Attack",
            title = "Teardrop",
            durationMs = 240_000L,
        )
        val claim = claim(job)
        val media = MuseActualMedia("flac", 900, 44_100, 16, 240_000L)
        val artifact = AcquiredTrackArtifact(
            localTrackId = "opaque-local-id",
            committedPath = file.absolutePath,
            uploadFile = file,
            deleteUploadFileAfterUse = false,
            sha256 = "a".repeat(64),
            sizeBytes = file.length(),
            media = media,
        )
        val ticket = MuseUploadTicket(
            id = "ticket-12345678",
            expiresAt = "2026-07-14T10:30:00.000Z",
            target = MuseUploadTarget.Direct("https://ingest.example.test/upload", "PUT", emptyMap()),
        )
        val receipt = MuseIngestReceipt("receipt-12345678", artifact.sha256)
        val descriptor = MuseArtifactDescriptor(
            artifact.localTrackId,
            artifact.sha256,
            artifact.sizeBytes,
            media,
        )

        coEvery { prefs.current() } returns config()
        coEvery { client.negotiateContract() } returns MuseAcquisitionContract.V2
        coEvery { client.claimAssigned(any()) } returns claim
        coEvery { client.reportPhase(job.id, LEASE_TOKEN, any()) } returns Unit
        coEvery { pipeline.acquireV2(job, any(), any()) } returns TrackAcquisitionOutcome.ArtifactReady(artifact)
        coEvery { client.requestUploadTicket(job.id, LEASE_TOKEN, descriptor) } returns ticket
        coEvery { client.uploadArtifact(ticket, file, artifact.sha256, any()) } returns receipt
        coEvery { client.completeV2(job.id, LEASE_TOKEN, receipt, descriptor) } returns Unit

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())

        coVerifyOrder {
            client.requestUploadTicket(job.id, LEASE_TOKEN, descriptor)
            client.uploadArtifact(ticket, file, artifact.sha256, any())
            client.completeV2(job.id, LEASE_TOKEN, receipt, descriptor)
        }
        coVerify(exactly = 0) { client.listApproved(any()) }
    }

    @Test
    fun `v2 upload failure never reports completion`() = runTest {
        val file = temporaryFolder.newFile("failed-upload.flac").apply { writeText("verified flac bytes") }
        val job = job().copy(artist = "Massive Attack", title = "Teardrop", durationMs = 240_000L)
        val claim = claim(job)
        val media = MuseActualMedia("flac", 900, 44_100, 16, 240_000L)
        val artifact = AcquiredTrackArtifact(
            "opaque-local-id",
            file.absolutePath,
            file,
            false,
            "a".repeat(64),
            file.length(),
            media,
        )
        val descriptor = MuseArtifactDescriptor(
            artifact.localTrackId,
            artifact.sha256,
            artifact.sizeBytes,
            media,
        )
        val ticket = MuseUploadTicket(
            "ticket-12345678",
            "2026-07-14T10:30:00.000Z",
            MuseUploadTarget.Direct("https://ingest.example.test/upload", "PUT", emptyMap()),
        )
        coEvery { prefs.current() } returns config()
        coEvery { client.negotiateContract() } returns MuseAcquisitionContract.V2
        coEvery { client.claimAssigned(any()) } returns claim
        coEvery { client.reportPhase(any(), any(), any()) } returns Unit
        coEvery { pipeline.acquireV2(job, any(), any()) } returns TrackAcquisitionOutcome.ArtifactReady(artifact)
        coEvery { client.requestUploadTicket(job.id, LEASE_TOKEN, descriptor) } returns ticket
        coEvery { client.uploadArtifact(ticket, file, artifact.sha256, any()) } throws
            MuseAcquisitionRemoteException("upload_network_error", retryable = true)
        coEvery { client.failV2(job.id, LEASE_TOKEN, "upload_network_error", true) } returns Unit

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        coVerify(exactly = 0) { client.completeV2(any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.failV2(job.id, LEASE_TOKEN, "upload_network_error", true) }
    }

    private fun worker() = MuseAcquisitionWorker(context, params, prefs, client, matcher, pipeline)

    private fun config() = MuseAcquisitionConfig(
        enabled = true,
        serverUrl = "https://muse.example.test/acquisition",
        token = "a-secure-token-with-at-least-32-characters",
        consumerId = "stash-test",
        wifiOnly = true,
        chargingOnly = true,
        lastAttemptAt = 0,
        lastSuccessAt = 0,
        lastResult = "",
        pendingCount = 0,
    )

    private fun job() = MuseAcquisitionJob(
        id = "123e4567-e89b-12d3-a456-426614174000",
        query = "Massive Attack Teardrop",
        attempt = 0,
        approvedAt = "2026-07-14T10:00:00.000Z",
        expiresAt = "2026-07-21T10:00:00.000Z",
    )

    private fun claim(job: MuseAcquisitionJob) = MuseAcquisitionClaim(
        job = job,
        leaseToken = LEASE_TOKEN,
        leaseExpiresAt = "2026-07-14T10:30:00.000Z",
    )

    private companion object {
        const val LEASE_TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
    }
}
