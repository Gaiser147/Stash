package com.stash.data.download.acquisition

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TrackItem
import com.stash.data.download.search.SearchDownloadCoordinator
import com.stash.data.download.search.SearchDownloadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class MuseAcquisitionWorkerTest {
    private val context: Context = mockk(relaxed = true)
    private val params: WorkerParameters = mockk(relaxed = true)
    private val prefs: MuseAcquisitionPreferences = mockk(relaxed = true)
    private val client: MuseAcquisitionClient = mockk()
    private val matcher: MuseAcquisitionMatcher = mockk()
    private val coordinator: SearchDownloadCoordinator = mockk()

    @Test
    fun `confident request is leased downloaded and completed`() = runTest {
        val job = job()
        val track = TrackItem("yt-id", "Teardrop", "Massive Attack", 240.0, null)
        coEvery { prefs.current() } returns config()
        coEvery { client.listApproved(3) } returns listOf(job)
        coEvery { matcher.findConfidentMatch(job.query) } returns track
        coEvery { client.claim(job.id) } returns claim(job)
        coEvery { client.reportDownloading(job.id, LEASE_TOKEN) } returns Unit
        every { coordinator.download(track) } returns flowOf(
            SearchDownloadStatus.Resolving,
            SearchDownloadStatus.Completed,
        )
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
        coEvery { client.listApproved(3) } returns listOf(job)
        coEvery { matcher.findConfidentMatch(job.query) } returns null
        coEvery { client.claim(job.id) } returns claim(job)
        coEvery { client.reportFailed(job.id, LEASE_TOKEN, "no_confident_match", false) } returns Unit

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { client.reportFailed(job.id, LEASE_TOKEN, "no_confident_match", false) }
        verify(exactly = 0) { coordinator.download(any()) }
    }

    @Test
    fun `worker cancellation is never converted into a retry`() {
        val job = job()
        coEvery { prefs.current() } returns config()
        coEvery { client.listApproved(3) } returns listOf(job)
        coEvery { matcher.findConfidentMatch(job.query) } throws CancellationException("stopped")

        assertThrows(CancellationException::class.java) { runTest { worker().doWork() } }
        coVerify(exactly = 0) { client.claim(any()) }
    }

    private fun worker() = MuseAcquisitionWorker(context, params, prefs, client, matcher, coordinator)

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
