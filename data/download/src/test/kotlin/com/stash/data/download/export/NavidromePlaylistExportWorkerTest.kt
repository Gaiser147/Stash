package com.stash.data.download.export

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.SyncNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The full export must survive Android stopping it: a restart resumes after
 * the last track it got through instead of re-sending the whole library, so
 * the playlists and the sync-complete call at the end are eventually reached.
 */
class NavidromePlaylistExportWorkerTest {

    private val server = "https://ingest.example.test"
    private val exportConfig = NavidromeExportConfig(
        enabled = true,
        serverUrl = server,
        token = "token",
        wifiOnly = false,
        chargingOnly = false,
        lastAttemptAt = 0,
        lastSuccessAt = 0,
        lastResult = "",
    )

    private val prefs: NavidromeExportPreferences = mockk(relaxUnitFun = true) {
        coEvery { current() } returns exportConfig
    }
    private val playlistDao: PlaylistDao = mockk {
        coEvery { getSyncEnabledPlaylists(any()) } returns emptyList()
    }
    private val trackDao: TrackDao = mockk()
    private val ingest: NavidromeIngestClient = mockk {
        coEvery { uploadCover(any(), any()) } returns NavidromeUploadOutcome.SkippedNoSource
        coEvery { syncComplete(any()) } returns NavidromeUploadOutcome.Success
    }
    private val covers: NavidromeCoverResolver = mockk {
        coEvery { resolve(any<TrackEntity>()) } returns null
    }
    private val constraints: NavidromeRuntimeConstraints = mockk {
        every { areSatisfied(any()) } returns true
    }
    private val params: WorkerParameters = mockk(relaxed = true) {
        every { inputData } returns Data.Builder().putBoolean(NavidromePlaylistExportWorker.KEY_FULL_EXPORT, true).build()
    }

    // A mocked Context makes WorkManager.getInstance throw inside the guarded
    // setForeground, which is exactly the "foreground refused" path.
    private fun worker() = NavidromePlaylistExportWorker(
        mockk<Context>(relaxed = true), params, prefs, playlistDao, trackDao, ingest,
        NavidromeUploadScheduler(mockk(relaxed = true), prefs), covers, constraints,
        mockk<SyncNotificationManager>(relaxed = true),
    )

    private fun track(id: Long) = TrackEntity(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        filePath = "/music/song$id.opus", isDownloaded = true,
    )

    @Test fun `a restarted export resumes after the last track it got through`() = runTest {
        // Unsorted on purpose: the resume point relies on id order.
        coEvery { trackDao.getAllDownloaded() } returns listOf(track(4), track(1), track(3), track(2))
        coEvery { prefs.fullExportProgress(server) } returns NavidromeFullExportProgress(afterTrackId = 2L)
        coEvery { ingest.uploadFile(any(), any(), any()) } returns NavidromeUploadOutcome.Success

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { ingest.uploadFile("/music/song1.opus", any(), any()) }
        coVerify(exactly = 0) { ingest.uploadFile("/music/song2.opus", any(), any()) }
        coVerify { ingest.uploadFile("/music/song3.opus", any(), any()) }
        coVerify { ingest.uploadFile("/music/song4.opus", any(), any()) }
        coVerify { prefs.saveFullExportProgress(server, NavidromeFullExportProgress(4L, emptySet())) }
        coVerify { ingest.syncComplete(any()) }
        // Finished: the next full export starts from the top again.
        coVerify { prefs.clearFullExportProgress() }
    }

    @Test fun `a retryable failure doesn't hold back the resume point, the track is retried alone`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..3L).map(::track)
        coEvery { prefs.fullExportProgress(server) } returns NavidromeFullExportProgress()
        coEvery { ingest.uploadFile(any(), any(), any()) } returns NavidromeUploadOutcome.Success
        coEvery { ingest.uploadFile("/music/song2.opus", any(), any()) } returns NavidromeUploadOutcome.RetryableFailure

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        // Later tracks and the playlists are still sent in this run...
        coVerify { ingest.uploadFile("/music/song3.opus", any(), any()) }
        coVerify { ingest.syncComplete(any()) }
        // ...the resume point moves past song 2, which is remembered for a retry.
        coVerify { prefs.saveFullExportProgress(server, NavidromeFullExportProgress(3L, setOf(2L))) }
        coVerify(exactly = 0) { prefs.clearFullExportProgress() }
    }

    @Test fun `the retry run sends only the failed tracks, then finishes`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..3L).map(::track)
        coEvery { prefs.fullExportProgress(server) } returns NavidromeFullExportProgress(3L, setOf(2L))
        coEvery { ingest.uploadFile(any(), any(), any()) } returns NavidromeUploadOutcome.Success

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { ingest.uploadFile(any(), any(), any()) }
        coVerify { ingest.uploadFile("/music/song2.opus", any(), any()) }
        coVerify { prefs.clearFullExportProgress() }
    }
}
