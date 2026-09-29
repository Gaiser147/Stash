package com.stash.feature.nowplaying

import android.content.Context
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** "Save mix as playlist" from Now Playing. */
class NowPlayingSaveMixTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val library = Track(id = 7L, title = "Owned", artist = "A", isDownloaded = true)
    // A discovery: synthetic id, no Room row yet.
    private val discovery = Track(id = -123L, title = "New", artist = "B", youtubeId = "vid", isStreamable = true)

    private val playerStateFlow = MutableStateFlow(PlayerState(queue = listOf(library, discovery)))

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns playerStateFlow
        every { currentPosition } returns MutableStateFlow(0L)
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }

    private fun newViewModel() = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = mockk(relaxed = true) { every { mirrorFailures } returns MutableSharedFlow() },
        losslessUpgrader = mockk(),
        lyricsRepository = mockk(relaxed = true),
        appContext = mockk<Context>(relaxed = true),
        ytMusicApiClient = mockk(relaxed = true),
    )

    @Test fun `saves the whole queue in order, persisting discoveries first`() = runTest(dispatcher) {
        coEvery { musicRepository.createPlaylist("Mix for you") } returns 50L
        coEvery { musicRepository.ensureTrackPersisted(library) } returns 7L
        coEvery { musicRepository.ensureTrackPersisted(discovery) } returns 900L

        newViewModel().saveQueueAsPlaylist("  Mix for you ")

        coVerifyOrder {
            musicRepository.createPlaylist("Mix for you")
            musicRepository.ensureTrackPersisted(library)
            musicRepository.addTrackToPlaylist(7L, 50L)
            musicRepository.ensureTrackPersisted(discovery)
            musicRepository.addTrackToPlaylist(900L, 50L)
        }
    }

    @Test fun `blank name does nothing`() = runTest(dispatcher) {
        newViewModel().saveQueueAsPlaylist("   ")

        coVerify(exactly = 0) { musicRepository.createPlaylist(any()) }
    }
}
