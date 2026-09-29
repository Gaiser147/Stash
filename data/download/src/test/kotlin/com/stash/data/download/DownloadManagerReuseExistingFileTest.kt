package com.stash.data.download

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song already on the phone at the path a download would commit to is
 * reused instead of fetched again (reinstall / second install on the same
 * library folder), but only for tracks that have no file yet.
 */
class DownloadManagerReuseExistingFileTest {

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true)
    private val albumMatchExecutor: AlbumMatchExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val losslessRegistry: LosslessSourceRegistry = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)
    private val navidromeExportScheduler: com.stash.core.data.sync.NavidromeExportScheduler =
        mockk(relaxed = true)

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = searchExecutor,
        albumMatchExecutor = albumMatchExecutor,
        matchScorer = mockk<MatchScorer>(relaxed = true),
        duplicateDetection = mockk<DuplicateDetectionService>(relaxed = true),
        fileOrganizer = fileOrganizer,
        qualityPrefs = mockk<QualityPreferencesManager>(relaxed = true),
        ytLibraryCanonicalizer = mockk<YtLibraryCanonicalizer>(relaxed = true),
        trackDao = mockk<TrackDao>(relaxed = true),
        playlistDao = playlistDao,
        lastFmApiClient = mockk<LastFmApiClient>(relaxed = true),
        lastFmCredentials = mockk<LastFmCredentials>(relaxed = true),
        losslessRegistry = losslessRegistry,
        losslessUrlDownloader = mockk<LosslessUrlDownloader>(relaxed = true),
        losslessPrefs = losslessPrefs,
        trackFinalizer = mockk<TrackFinalizer>(relaxed = true),
        loudnessMeasurer = mockk(relaxed = true),
        metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true),
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = lyricsFetchTrigger,
        audioDurationExtractor = mockk(relaxed = true),
        losslessHealthGate = mockk(relaxed = true),
        navidromeExportScheduler = navidromeExportScheduler,
    )

    private val track = Track(id = 7L, title = "Levels", artist = "Avicii", album = "")

    @Test
    fun `a file already in the library is reused without fetching`() = runTest {
        coEvery { fileOrganizer.findExistingTrackFile("Avicii", null, "Levels") } returns
            "content://tree/Music/avicii/singles/levels.opus"

        val result = newSubject().downloadTrack(track, preResolvedUrl = "https://youtube.test/watch?v=x")

        assertEquals(TrackDownloadResult.Success("content://tree/Music/avicii/singles/levels.opus"), result)
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
        coVerify {
            navidromeExportScheduler.enqueueTrack(
                "content://tree/Music/avicii/singles/levels.opus", "Avicii", null, "Levels",
                any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `a track that already has a file is fetched again (re-download)`() = runTest {
        coEvery { fileOrganizer.findExistingTrackFile(any(), any(), any()) } returns "/music/avicii/singles/levels.opus"
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns null
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns emptyList()

        val redownload = track.copy(isDownloaded = true, filePath = "/music/avicii/singles/levels.opus")
        val result = newSubject().downloadTrack(redownload, preResolvedUrl = null)

        // It went through the normal pipeline (here: no match), not the reuse shortcut.
        assertTrue("expected the normal pipeline, got $result", result is TrackDownloadResult.Unmatched)
        coVerify(exactly = 0) { fileOrganizer.findExistingTrackFile(any(), any(), any()) }
    }
}
