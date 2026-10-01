package com.stash.core.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.autoplay.AutoplayEngine
import com.stash.core.data.autoplay.AutoplaySession
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryAutoplayTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val radioGenerator: RadioStationGenerator = mockk()
    private val engine: AutoplayEngine = mockk()
    private val session: AutoplaySession = mockk(relaxed = true)

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = mockk<StreamSourceRegistry>(),
            streamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true),
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = radioGenerator,
            autoplayEngine = engine,
        )
        repo.controllerDeferred = controller
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns false
        coEvery { engine.isEnabled() } returns true
        coEvery { engine.start(any(), any()) } returns session
        coEvery { engine.nextBatch(session, any(), any(), any()) } returns listOf(track(10), track(11))
        // No timeline by default → growers use the logical queue count.
        every { controller.currentTimeline } returns Timeline.EMPTY
    }

    /**
     * A 10-item timeline whose shuffle order is [shuffleOrder] (timeline
     * indices in play order); linear order is 0..9.
     */
    private fun stubTimeline(shuffle: Boolean, current: Int, shuffleOrder: List<Int> = (0..9).toList()) {
        val timeline = mockk<Timeline>()
        every { timeline.isEmpty } returns false
        every { timeline.getNextWindowIndex(any(), Player.REPEAT_MODE_OFF, any()) } answers {
            val idx = firstArg<Int>()
            val order = if (thirdArg<Boolean>()) shuffleOrder else (0..9).toList()
            val pos = order.indexOf(idx)
            if (pos < 0 || pos + 1 >= order.size) C.INDEX_UNSET else order[pos + 1]
        }
        every { controller.currentTimeline } returns timeline
        every { controller.shuffleModeEnabled } returns shuffle
        every { controller.currentMediaItemIndex } returns current
    }

    private fun track(id: Long) = Track(id = id, title = "t$id", artist = "a$id", youtubeId = "v$id", isStreamable = true)

    /** Runs coroutines launched on the repository's Main scope (armAutoplay). */
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `setQueue arms autoplay and growAutoplay appends the next batch`() = runTest {
        repo.setQueue(listOf(track(1), track(2)))
        idleMain()

        repo.growAutoplay()

        coVerify { engine.start(match { q -> q.map { it.id } == listOf(1L, 2L) }, any()) }
        // Offline (no connectivity) → no stream-only rows and no discoveries requested.
        coVerify { engine.nextBatch(session, includeStreamable = false, allowDiscovery = false, any()) }
        verify { controller.addMediaItems(match<List<MediaItem>> { it.size == 2 }) }
    }

    @Test fun `disabled autoplay never appends`() = runTest {
        coEvery { engine.isEnabled() } returns false
        repo.setQueue(listOf(track(1)))
        idleMain()

        repo.growAutoplay()

        coVerify(exactly = 0) { engine.nextBatch(any(), any(), any(), any()) }
        verify(exactly = 0) { controller.addMediaItems(any<List<MediaItem>>()) }
    }

    @Test fun `starting a radio station disarms autoplay`() = runTest {
        repo.setQueue(listOf(track(1)))
        idleMain()
        val radio = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (radio to listOf(track(5)))
        repo.startRadio(RadioSeed.Artist("MBV", "id"))

        repo.growAutoplay()

        coVerify(exactly = 0) { engine.nextBatch(any(), any(), any(), any()) }
    }

    @Test fun `growAutoplay is a no-op before any queue was started`() = runTest {
        repo.growAutoplay()

        coVerify(exactly = 0) { engine.nextBatch(any(), any(), any(), any()) }
    }

    @Test fun `shuffle - no autoplay while playlist songs are still unplayed`() = runTest {
        repo.setQueue((1L..10L).map(::track))
        idleMain()
        // Playing timeline index 9 (last in LINEAR order) but only 3rd in shuffle order.
        stubTimeline(shuffle = true, current = 9, shuffleOrder = listOf(4, 2, 9, 0, 1, 3, 5, 6, 7, 8))

        repo.growAutoplay()

        coVerify(exactly = 0) { engine.nextBatch(any(), any(), any(), any()) }
    }

    @Test fun `shuffle - autoplay starts on the last unplayed song`() = runTest {
        repo.setQueue((1L..10L).map(::track))
        idleMain()
        stubTimeline(shuffle = true, current = 8, shuffleOrder = listOf(4, 2, 9, 0, 1, 3, 5, 6, 7, 8))

        repo.growAutoplay()

        coVerify { engine.nextBatch(session, any(), any(), any()) }
    }

    @Test fun `linear - autoplay starts with fewer than two songs left`() = runTest {
        repo.setQueue((1L..10L).map(::track))
        idleMain()
        stubTimeline(shuffle = false, current = 7)
        repo.growAutoplay()
        coVerify(exactly = 0) { engine.nextBatch(any(), any(), any(), any()) }

        stubTimeline(shuffle = false, current = 8)
        repo.growAutoplay()
        coVerify { engine.nextBatch(session, any(), any(), any()) }
    }

    @Test fun `a batch built right after a skip is ranked with that skip reported`() = runTest {
        coEvery { engine.recordOutcome(any(), any(), any(), any()) } returns Unit
        repo.setQueue(listOf(track(1), track(2)))
        idleMain()
        repo._playerState.value = PlayerState(currentTrack = track(1), queue = listOf(track(1), track(2)), currentIndex = 0)
        repo.growAutoplay()

        // Song 1 skipped; the grow for song 2 runs before the position collector notices.
        repo._playerState.value = PlayerState(currentTrack = track(2), queue = listOf(track(1), track(2)), currentIndex = 1)
        repo.growAutoplay()

        coVerifyOrder {
            engine.recordOutcome(session, match { it.id == 1L }, any(), any())
            engine.nextBatch(session, any(), any(), any())
        }
        coVerify(exactly = 1) { engine.recordOutcome(any(), any(), any(), any()) }
    }

    @Test fun `startPersonalMix plays the generated mix and flags it`() = runTest {
        coEvery { engine.buildMix(any(), any(), any(), any()) } returns listOf(track(21), track(22), track(23))

        val started = repo.startPersonalMix()

        assertThat(started).isTrue()
        assertThat(repo.personalMixActive.value).isTrue()
        verify { controller.setMediaItems(match<List<MediaItem>> { it.size == 3 }, 0, 0L) }

        // Any other queue replaces the mix.
        repo.setQueue(listOf(track(1)))
        assertThat(repo.personalMixActive.value).isFalse()
    }

    @Test fun `startPersonalMix reports failure when nothing could be built`() = runTest {
        coEvery { engine.buildMix(any(), any(), any(), any()) } returns emptyList()

        assertThat(repo.startPersonalMix()).isFalse()
        assertThat(repo.personalMixActive.value).isFalse()
        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
    }

    @Test fun `a queue started in the car is adopted - autoplay continues it`() = runTest {
        repo.adoptExternalQueue(listOf(track(1), track(2)))
        idleMain()

        repo.growAutoplay()

        coVerify { engine.start(match { q -> q.map { it.id } == listOf(1L, 2L) }, any()) }
        coVerify { engine.nextBatch(session, any(), any(), any()) }
        verify { controller.addMediaItems(match<List<MediaItem>> { it.size == 2 }) }
        assertThat(repo.personalMixActive.value).isFalse()
    }

    @Test fun `a mix started in the car shows as the personal mix`() = runTest {
        repo.adoptExternalQueue(listOf(track(21), track(22)), personalMix = true)
        idleMain()

        assertThat(repo.personalMixActive.value).isTrue()
    }

    @Test fun `adopting a car queue ends a running radio station`() = runTest {
        val radio = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (radio to listOf(track(5)))
        coEvery { radioGenerator.nextBatch(any()) } returns emptyList()
        repo.startRadio(RadioSeed.Artist("MBV", "id"))

        repo.adoptExternalQueue(listOf(track(1)))
        idleMain()
        repo.growAutoplay()

        coVerify { engine.nextBatch(session, any(), any(), any()) }
    }

    @Test fun `more like this replaces what follows the current song and keeps autoplay on it`() = runTest {
        every { controller.currentMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 5
        repo._playerState.value = PlayerState(currentTrack = track(2), queue = (1L..5L).map(::track), currentIndex = 1)
        coEvery { engine.start(listOf(track(2)), any()) } returns session
        coEvery { engine.nextBatch(session, any(), any(), 15) } returns listOf(track(30), track(31))

        assertThat(repo.moreLikeThis()).isTrue()

        verify { controller.removeMediaItems(2, 5) }
        verify { controller.addMediaItems(match<List<MediaItem>> { items -> items.map { it.mediaId } == listOf("30", "31") }) }
    }

    @Test fun `more like this without a current song does nothing`() = runTest {
        assertThat(repo.moreLikeThis()).isFalse()
        verify(exactly = 0) { controller.removeMediaItems(any(), any()) }
    }
}
