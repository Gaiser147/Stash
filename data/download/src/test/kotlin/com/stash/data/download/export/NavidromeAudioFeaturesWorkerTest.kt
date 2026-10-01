package com.stash.data.download.export

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.AudioFeaturesDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.AudioFeaturesEntity
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeAudioFeaturesWorkerTest {

    private val server = "https://ingest.example.test"
    private val exportConfig = NavidromeExportConfig(
        enabled = true, serverUrl = server, token = "t", wifiOnly = false, chargingOnly = false,
        lastAttemptAt = 0, lastSuccessAt = 0, lastResult = "",
    )
    private val prefs: NavidromeExportPreferences = mockk(relaxUnitFun = true) {
        coEvery { current() } returns exportConfig
    }
    private val client: NavidromeIngestClient = mockk()
    private val dao: AudioFeaturesDao = mockk(relaxUnitFun = true) {
        coEvery { unlinkMissingTracks() } returns 0
        coEvery { resolvedCount() } returns 0
    }
    private val trackDao: TrackDao = mockk()

    private fun worker() = NavidromeAudioFeaturesWorker(
        mockk<Context>(relaxed = true), mockk<WorkerParameters>(relaxed = true), prefs, client, dao, trackDao,
        NavidromeUploadScheduler(mockk(relaxed = true), prefs),
    )

    private fun remote(path: String, seq: Long) = RemoteAudioFeatures(
        path = path, seq = seq, version = 1, bpm = 120f, beatConfidence = 0.8f, beatRegularity = 0.9f,
        loudnessDb = -10f, dynamicsDb = 6f, brightnessHz = 2500f, onsetRate = 3f, pitchClass = 0,
        minor = false, keyStrength = 0.7f,
    )

    @Test fun `pages from the saved cursor and links features to downloaded tracks by upload path`() = runTest {
        coEvery { prefs.audioFeaturesCursor(server) } returns 10L
        coEvery { client.fetchAudioFeatures(10L, any()) } returns
            AudioFeaturesPage(listOf(remote("avicii/singles/levels.opus", 11), remote("other/x/y.flac", 12)), 12L)
        coEvery { client.fetchAudioFeatures(12L, any()) } returns AudioFeaturesPage(emptyList(), 12L)
        coEvery { dao.unresolvedPaths() } returns listOf("avicii/singles/levels.opus", "other/x/y.flac")
        coEvery { trackDao.getAllDownloaded() } returns listOf(
            TrackEntity(id = 7, title = "Levels", artist = "Avicii", album = "", filePath = "content://tree/levels.opus", isDownloaded = true),
            TrackEntity(id = 8, title = "Other", artist = "Someone", album = "Album", filePath = "/music/other.m4a", isDownloaded = true),
        )
        val stored = slot<List<AudioFeaturesEntity>>()
        coEvery { dao.upsertAll(capture(stored)) } returns Unit

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(stored.captured.map { it.path }).containsExactly("avicii/singles/levels.opus", "other/x/y.flac")
        assertThat(stored.captured.all { it.trackId == null }).isTrue()
        coVerify { prefs.saveAudioFeaturesCursor(server, 12L) }
        coVerify { dao.resolve("avicii/singles/levels.opus", 7L) }
        coVerify(exactly = 1) { dao.resolve(any(), any()) }
    }

    @Test fun `an unreachable server keeps the cursor and still links what is stored`() = runTest {
        coEvery { prefs.audioFeaturesCursor(server) } returns 3L
        coEvery { client.fetchAudioFeatures(3L, any()) } returns null
        coEvery { dao.unresolvedPaths() } returns emptyList()

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())

        coVerify(exactly = 0) { prefs.saveAudioFeaturesCursor(any(), any()) }
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }
}
