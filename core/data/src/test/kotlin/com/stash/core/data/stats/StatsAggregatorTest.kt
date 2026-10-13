package com.stash.core.data.stats

import com.stash.core.data.db.dao.HeardSound
import com.stash.core.data.db.dao.PlayRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class StatsAggregatorTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val hour = 3_600_000L

    private fun play(at: Long, listened: Long? = null, duration: Long = 200_000) =
        PlayRow(trackId = 1, startedAt = at, listenedMs = listened, durationMs = null, trackDurationMs = duration)

    @Test fun `legacy plays count four minutes at most, new plays their real depth`() {
        assertEquals(120_000L, StatsAggregator.listenedMs(play(0, listened = 120_000)))
        assertEquals(200_000L, StatsAggregator.listenedMs(play(0, duration = 200_000)))
        assertEquals(StatsAggregator.LEGACY_PLAY_MS, StatsAggregator.listenedMs(play(0, duration = 600_000)))
    }

    @Test fun `per day puts today last`() {
        val now = 10 * day + 12 * hour
        val rows = listOf(play(now - hour, 60_000), play(now - day, 30_000), play(now - 9 * day, 1))
        val out = StatsAggregator.perDay(rows, days = 7, now = now, zone = utc)
        assertEquals(60_000L, out[6])
        assertEquals(30_000L, out[5])
        assertEquals(90_000L, out.sum())
    }

    @Test fun `hours and weekdays`() {
        // 1970-01-05 was a Monday.
        val monday9 = 4 * day + 9 * hour
        val rows = listOf(play(monday9, 60_000), play(monday9 + day, 30_000))
        assertEquals(60_000L, StatsAggregator.perHour(listOf(rows[0]), utc)[9])
        val wh = StatsAggregator.weekdayHour(rows, utc)
        assertEquals(60_000L, wh[0][9])
        assertEquals(30_000L, wh[1][9])
    }

    @Test fun `sound distribution`() {
        fun s(bpm: Float, loud: Float, minor: Boolean) = HeardSound(0, bpm, 0.8f, loud, 2000f, 2f, minor, 0.6f)
        val sound = StatsAggregator.sound(listOf(s(80f, -25f, true), s(120f, -10f, false), s(125f, -8f, true)), utc)
        assertEquals(3, sound.plays)
        assertEquals(1f / 3, sound.tempo[0], 1e-4f)
        assertEquals(2f / 3, sound.tempo[2], 1e-4f)
        assertEquals(2f / 3, sound.minorShare, 1e-4f)
        assertEquals(120f, sound.medianBpm!!, 0f)
        assertTrue(sound.energy.sum() in 0.99f..1.01f)
    }
}
