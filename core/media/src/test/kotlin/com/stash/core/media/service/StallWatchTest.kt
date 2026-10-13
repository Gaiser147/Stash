package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StallWatchTest {

    private val watch = StallWatch(frozenMs = 4_000, bufferingMs = 20_000)

    private fun tick(t: Long, pos: Long, ready: Boolean = true, buffering: Boolean = false, pwr: Boolean = true, suppressed: Boolean = false) =
        watch.sample(t, pwr, ready, buffering, suppressed, pos)

    @Test fun `moving playback is fine`() {
        for (t in 0L..10_000L step 1_000) assertThat(tick(t, pos = t)).isEqualTo(StallWatch.Action.NONE)
    }

    @Test fun `a frozen position while playing is re-prepared, then skipped`() {
        assertThat(tick(0, 5_000)).isEqualTo(StallWatch.Action.NONE)
        assertThat(tick(3_000, 5_000)).isEqualTo(StallWatch.Action.NONE)
        assertThat(tick(4_000, 5_000)).isEqualTo(StallWatch.Action.REPREPARE)
        assertThat(tick(5_000, 5_000)).isEqualTo(StallWatch.Action.NONE)
        assertThat(tick(9_000, 5_000)).isEqualTo(StallWatch.Action.SKIP)
    }

    @Test fun `a user pause or a suppressed route never counts`() {
        for (t in 0L..10_000L step 1_000) {
            assertThat(tick(t, 5_000, pwr = false)).isEqualTo(StallWatch.Action.NONE)
            assertThat(tick(t, 5_000, suppressed = true)).isEqualTo(StallWatch.Action.NONE)
        }
    }

    @Test fun `endless buffering is recovered after the limit`() {
        assertThat(tick(0, 0, ready = false, buffering = true)).isEqualTo(StallWatch.Action.NONE)
        assertThat(tick(19_000, 0, ready = false, buffering = true)).isEqualTo(StallWatch.Action.NONE)
        assertThat(tick(20_000, 0, ready = false, buffering = true)).isEqualTo(StallWatch.Action.REPREPARE)
    }

    @Test fun `a new song starts the count over`() {
        tick(0, 5_000); tick(4_000, 5_000) // first recovery
        watch.reset()
        tick(5_000, 0); assertThat(tick(9_000, 0)).isEqualTo(StallWatch.Action.REPREPARE)
    }
}
