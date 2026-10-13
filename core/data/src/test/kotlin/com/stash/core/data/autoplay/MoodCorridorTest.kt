package com.stash.core.data.autoplay

import com.stash.core.data.db.dao.HeardSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MoodCorridorTest {

    private fun profile(loudness: Float, bpm: Float = 120f, onsets: Float = 3f, brightness: Float = 2500f) =
        AudioProfile(bpm, 0.8f, loudness, brightness, onsets, 0, false, 0.7f)

    private val calm = profile(loudness = -22f, bpm = 80f, onsets = 1f, brightness = 1400f)
    private val loud = profile(loudness = -5f, bpm = 150f, onsets = 6f, brightness = 4200f)

    @Test fun `songs inside the band fit and loud songs fall out of a calm one`() {
        val corridor = MoodCorridor.around(calm)
        assertEquals(1f, corridor.fit(calm), 1e-4f)
        assertTrue("fit=${corridor.fit(loud)}", corridor.fit(loud) < 0.1f)
    }

    @Test fun `a tempo limit from a request pushes fast songs out`() {
        val slow = MoodCorridor(energyCenter = AudioFlow.energy(calm), energyWidth = 1f, bpmMax = 100f)
        assertEquals(1f, slow.fit(calm), 1e-4f)
        assertTrue(slow.fit(profile(loudness = -22f, bpm = 140f, onsets = 1f, brightness = 1400f)) < 0.2f)
    }

    @Test fun `the band widens until enough of the library fits`() {
        val library = (0 until 20).map { profile(loudness = -30f + it) }
        val narrow = MoodCorridor(energyCenter = 0.05f, energyWidth = 0.01f)
        val fitted = MoodCorridor.fitted(narrow, library)
        assertTrue(fitted.energyWidth > narrow.energyWidth)
        assertTrue(library.count { fitted.fit(it) >= 0.5f } >= MoodCorridor.MIN_INSIDE || fitted.energyWidth >= narrow.energyWidth * 3.3f)
    }

    @Test fun `ranking pushes an out-of-band song below a weaker in-band one`() {
        val strongButLoud = AutoplayCandidate("a|1", "A", "1", AutoplayOrigin.LIBRARY, sessionSim = 1f, affinity = 1f, corridorFit = 0f)
        val weakButCalm = AutoplayCandidate("b|1", "B", "1", AutoplayOrigin.LIBRARY, sessionSim = 0.5f, affinity = 0.4f, corridorFit = 1f)
        assertTrue(AutoplayRanker.score(weakButCalm) > AutoplayRanker.score(strongButLoud))
    }

    @Test fun `mood tags agree, clash or stay neutral`() {
        val chill = MoodSpace.moodsOf(mapOf("chillout" to 80f, "mellow" to 40f))
        val party = MoodSpace.moodsOf(mapOf("party" to 90f, "club" to 50f))
        assertTrue(MoodSpace.fit(chill, chill) > 0.9f)
        assertTrue(MoodSpace.fit(chill, party) < 0.2f)
        assertTrue(MoodSpace.clashes(chill, party))
        assertFalse(MoodSpace.clashes(chill, chill))
        assertEquals(MoodSpace.NEUTRAL, MoodSpace.fit(chill, emptyMap()), 0f)
        assertTrue(MoodSpace.moodsOf(mapOf("rock" to 100f)).isEmpty())
    }

    @Test fun `variants pass only when the library collects them`() {
        val share = VariantKind.shares(
            (1..20).map { "Nightcore Zodiac Nightcore - Song $it" } + (1..80).map { "Artist $it Song $it" },
        )
        assertTrue(VariantKind.allowed("Into The Nightcore Just a Dream", share))
        assertFalse(VariantKind.allowed("Coldplay Yellow (Live at Wembley)", share))
        assertTrue(VariantKind.allowed("Coldplay Yellow", share))
        assertFalse(VariantKind.allowed("Song (sped up)", emptyMap()))
    }

    @Test fun `time of day profile needs enough plays per window`() {
        val utc = TimeZone.getTimeZone("UTC")
        val hour = 3_600_000L
        fun play(atHour: Int, loudness: Float) = HeardSound(atHour * hour, 100f, 0.8f, loudness, 2000f, 2f, false, 0.5f)
        val rows = (1..20).map { play(1, -25f) } + (1..20).map { play(19, -6f) } + (1..3).map { play(10, -10f) }
        val profile = TimeOfDayEnergy.profile(rows, utc)
        val night = profile[0]!!
        val evening = profile[6]!!
        assertTrue(night < evening)
        assertNull(profile[3])
    }

    @Test fun `flat and early-peak arcs differ from the default rise`() {
        val n = 20
        val flat = (0 until n).map { MixArc.target(it, n, ArcShape.FLAT) }
        assertTrue(flat.max() - flat.min() <= 0.31f)
        val early = (0 until n).map { MixArc.target(it, n, ArcShape.PEAK_EARLY) }
        assertTrue(early.indexOf(early.max()) < n / 3)
        val rise = (0 until n).map { MixArc.target(it, n, ArcShape.RISE) }
        assertTrue(rise.indexOf(rise.max()) > n / 2)
    }
}
