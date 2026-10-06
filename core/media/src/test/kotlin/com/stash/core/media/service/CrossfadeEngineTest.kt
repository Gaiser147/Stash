package com.stash.core.media.service

import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The crossfade must never swap the session's player: Android Auto and the
 * app follow one player for good, and the next song continues on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CrossfadeEngineTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    /** Master with a 2-song queue playing song "a"; after its seek it plays "b". */
    private class FakeMaster(val player: ExoPlayer) {
        var index = 0
        var position = 0L
    }

    @Test fun `after the fade the master plays the next song at the spare's position - no swap`() = runTest {
        val a = item("a"); val b = item("b")
        val masterState = FakeMaster(mockk(relaxed = true))
        val master = masterState.player
        every { master.mediaItemCount } returns 2
        every { master.getMediaItemAt(0) } returns a
        every { master.getMediaItemAt(1) } returns b
        every { master.currentMediaItemIndex } answers { masterState.index }
        every { master.currentMediaItem } answers { if (masterState.index == 0) a else b }
        every { master.nextMediaItemIndex } answers { if (masterState.index == 0) 1 else -1 }
        every { master.isPlaying } returns true
        every { master.currentPosition } answers { masterState.position }
        every { master.seekTo(any<Int>(), any<Long>()) } answers {
            masterState.index = firstArg(); masterState.position = secondArg()
        }
        val spare: ExoPlayer = mockk(relaxed = true)
        every { spare.mediaItemCount } returns 1
        every { spare.currentMediaItem } returns b
        every { spare.isPlaying } returns true
        every { spare.playbackState } returns Player.STATE_READY
        every { spare.currentPosition } returns 6_000L

        val players = ArrayDeque(listOf(master, spare))
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val engine = CrossfadeEngine(context, { players.removeFirst() }, scope)
        engine.initialize()
        var done = false

        engine.performTransition(fadeMs = 2_000) { done = true }
        scope.advanceUntilIdle()

        assertThat(done).isTrue()
        assertThat(engine.masterPlayer).isSameInstanceAs(master)
        verify { master.seekTo(1, 6_300L) }
        verify { spare.stop() }
        assertThat(engine.isTransitioning()).isFalse()
        assertThat(engine.isHandingOff()).isFalse()
        verify(exactly = 0) { master.pause() }
    }

    @Test fun `a next song that never starts aborts the fade without touching the master's queue`() = runTest {
        val master: ExoPlayer = mockk(relaxed = true)
        val spare: ExoPlayer = mockk(relaxed = true)
        every { spare.mediaItemCount } returns 1
        every { spare.currentMediaItem } returns item("b")
        every { spare.isPlaying } returns false
        val players = ArrayDeque(listOf(master, spare))
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val engine = CrossfadeEngine(context, { players.removeFirst() }, scope)
        engine.initialize()

        engine.performTransition(fadeMs = 2_000) {}
        scope.advanceUntilIdle()

        assertThat(engine.abortedMediaId).isEqualTo("b")
        verify(exactly = 0) { master.seekTo(any<Int>(), any<Long>()) }
        assertThat(engine.isTransitioning()).isFalse()
    }
}
