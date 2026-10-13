package com.stash.data.download

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.MusicSource
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.navidrome.NavidromeServerConfig
import com.stash.data.download.navidrome.NavidromeSource
import com.stash.data.download.navidrome.SubsonicSong
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals

/**
 * Download speed: songs only waiting (on a lossless rate-limit token) must
 * not hold the heavy-work slots, and songs the user's own Navidrome already
 * has come from there without touching any other source.
 */
class DownloadManagerSpeedTest {

    private val ownServer: NavidromeSource = mockk(relaxed = true)
    private val timings = DownloadTimings()

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true)
    private val albumMatchExecutor: AlbumMatchExecutor = mockk(relaxed = true)
    private val matchScorer: MatchScorer = mockk(relaxed = true)
    private val duplicateDetection: DuplicateDetectionService = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val ytLibraryCanonicalizer: YtLibraryCanonicalizer = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk(relaxed = true)
    private val losslessRegistry: LosslessSourceRegistry = mockk(relaxed = true)
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val trackFinalizer: TrackFinalizer = mockk(relaxed = true)
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer = mockk(relaxed = true)
    private val metadataEmbedder: MetadataEmbedder = mockk(relaxed = true)
    private val albumArtCache: AlbumArtCache = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor =
        mockk(relaxed = true)
    private val losslessHealthGate: com.stash.data.download.lossless.LosslessSourceHealthGate =
        mockk(relaxed = true)
    private val navidromeExportScheduler: com.stash.core.data.sync.NavidromeExportScheduler =
        mockk(relaxed = true)

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = searchExecutor,
        albumMatchExecutor = albumMatchExecutor,
        matchScorer = matchScorer,
        duplicateDetection = duplicateDetection,
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = ytLibraryCanonicalizer,
        trackDao = trackDao,
        playlistDao = playlistDao,
        lastFmApiClient = lastFmApiClient,
        lastFmCredentials = lastFmCredentials,
        losslessRegistry = losslessRegistry,
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = losslessPrefs,
        trackFinalizer = trackFinalizer,
        loudnessMeasurer = loudnessMeasurer,
        metadataEmbedder = metadataEmbedder,
        albumArtCache = albumArtCache,
        lyricsFetchTrigger = lyricsFetchTrigger,
        audioDurationExtractor = audioDurationExtractor,
        losslessHealthGate = losslessHealthGate,
        navidromeExportScheduler = navidromeExportScheduler,
        ownServer = ownServer,
        timings = timings,
    )

    private fun track(id: Long) = Track(id = id, title = "Song $id", artist = "Artist")

    @Test
    fun `a song waiting on a lossless token does not block a song that only needs yt-dlp`() = runTest {
        coEvery { ownServer.find(any()) } returns null
        // Songs 1..10 are Stash-Mix (forced lossless); their resolve waits.
        coEvery { playlistDao.isTrackInStashMix(any()) } answers { firstArg<Long>() <= 10L }
        coEvery { losslessPrefs.enabledNow() } returns false
        val gate = CompletableDeferred<Unit>()
        coEvery { losslessRegistry.resolve(any()) } coAnswers { gate.await(); null }
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns DownloadResult.Error("stop")
        val subject = newSubject()

        val waiting = (1L..10L).map { id -> async { subject.downloadTrack(track(id), preResolvedUrl = "https://youtu.be/x$id") } }
        // 10 songs > the 8 work slots: before, all slots were asleep in the resolve.
        val fast = subject.downloadTrack(track(99), preResolvedUrl = "https://youtu.be/fast")

        assertTrue("the plain yt-dlp song ran while 10 others waited, got $fast", fast is TrackDownloadResult.Failed)
        gate.complete(Unit)
        waiting.awaitAll()
    }

    @Test
    fun `a song already on the own server comes from there - no lossless, no yt-dlp, no re-upload`() = runTest {
        val config = NavidromeServerConfig("https://nd.example", "u", "p", scrobbleEnabled = false)
        val hit = NavidromeSource.Hit(config, SubsonicSong("s1", "Artist", "Song 5", 200, emptyList(), suffix = "flac"))
        coEvery { ownServer.find(any()) } returns hit
        every { fileOrganizer.getTempDir() } returns java.nio.file.Files.createTempDirectory("dlspeed").toFile()
        coEvery { ownServer.fetch(hit, any(), any()) } returns true
        coEvery { trackFinalizer.finalizeFile(any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(CommittedTrack("/music/Artist/Song 5.flac", 10), null)

        val result = newSubject().downloadTrack(track(5))

        assertEquals(TrackDownloadResult.Success("/music/Artist/Song 5.flac"), result)
        coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { navidromeExportScheduler.enqueueTrack(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("navidrome", timings.snapshot().single().source)
    }

    @Test
    fun `an own-server miss falls through to the usual chain and timings record the outcome`() = runTest {
        coEvery { ownServer.find(any()) } returns null
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { playlistDao.isTrackInStashMix(any()) } returns false
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns DownloadResult.Error("boom")

        val result = newSubject().downloadTrack(track(7), preResolvedUrl = "https://youtu.be/x")

        assertTrue(result is TrackDownloadResult.Failed)
        coVerify { downloadExecutor.download(any(), any(), any(), any(), any()) }
        assertEquals("failed", timings.snapshot().single().outcome)
    }

    @Test
    fun `with lossless wanted, a lossy copy on the own server is skipped`() = runTest {
        val config = NavidromeServerConfig("https://nd.example", "u", "p", scrobbleEnabled = false)
        coEvery { ownServer.find(any()) } returns
            NavidromeSource.Hit(config, SubsonicSong("s2", "Artist", "Song 8", 200, emptyList(), suffix = "opus"))
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { playlistDao.isTrackInStashMix(any()) } returns false
        coEvery { losslessRegistry.resolve(any()) } returns null

        val result = newSubject().downloadTrack(track(8))

        assertTrue(result is TrackDownloadResult.Deferred)
        coVerify(exactly = 0) { ownServer.fetch(any(), any(), any()) }
        coVerify { losslessRegistry.resolve(any()) }
    }
}
