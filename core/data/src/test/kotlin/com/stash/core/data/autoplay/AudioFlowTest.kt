package com.stash.core.data.autoplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFlowTest {

    private fun profile(
        bpm: Float = 120f,
        loudness: Float = -10f,
        brightness: Float = 2500f,
        onsets: Float = 3f,
        pitchClass: Int = 0,
        minor: Boolean = false,
    ) = AudioProfile(bpm, 0.8f, loudness, brightness, onsets, pitchClass, minor, 0.7f)

    @Test fun `half and double time count as the same tempo`() {
        assertEquals(0f, AudioFlow.tempoDistance(70f, 140f), 1e-5f)
        assertEquals(0f, AudioFlow.tempoDistance(180f, 90f), 1e-5f)
        assertTrue(AudioFlow.tempoDistance(120f, 128f) < AudioFlow.tempoDistance(120f, 100f))
    }

    @Test fun `camelot numbers match the DJ wheel`() {
        assertEquals(8, AudioFlow.camelotNumber(0, minor = false)) // C major = 8B
        assertEquals(9, AudioFlow.camelotNumber(7, minor = false)) // G major = 9B
        assertEquals(7, AudioFlow.camelotNumber(5, minor = false)) // F major = 7B
        assertEquals(8, AudioFlow.camelotNumber(9, minor = true)) // A minor = 8A
        assertEquals(1, AudioFlow.camelotNumber(8, minor = true)) // G# minor = 1A
    }

    @Test fun `harmonic neighbours mix better than distant keys`() {
        val c = profile(pitchClass = 0)
        val same = AudioFlow.keyCompatibility(c, profile(pitchClass = 0))
        val fifth = AudioFlow.keyCompatibility(c, profile(pitchClass = 7))
        val relative = AudioFlow.keyCompatibility(c, profile(pitchClass = 9, minor = true))
        val tritone = AudioFlow.keyCompatibility(c, profile(pitchClass = 6))
        assertEquals(1f, same, 0f)
        assertTrue(fifth > 0.8f && relative > 0.8f)
        assertTrue(tritone < 0.3f)
    }

    @Test fun `similarity prefers close tempo, loudness and brightness`() {
        val now = profile()
        val close = AudioFlow.similarity(now, profile(bpm = 123f, loudness = -11f, brightness = 2600f))
        val farTempo = AudioFlow.similarity(now, profile(bpm = 95f))
        val quiet = AudioFlow.similarity(now, profile(loudness = -24f))
        val dark = AudioFlow.similarity(now, profile(brightness = 900f))
        assertTrue(close > 0.9f)
        assertTrue(close > farTempo && close > quiet && close > dark)
        assertTrue(AudioFlow.similarity(now, now) > 0.99f)
    }

    @Test fun `blend weights recent songs and takes the newest key`() {
        assertNull(AudioFlow.blend(emptyList()))
        val blended = AudioFlow.blend(
            listOf(profile(bpm = 120f, loudness = -8f, pitchClass = 2) to 1f, profile(bpm = 60f, loudness = -12f, pitchClass = 5) to 1f),
        )!!
        // 60 BPM folds onto 120 (half time), so the blend stays at 120.
        assertEquals(120f, blended.bpm, 0.5f)
        assertEquals(-10f, blended.loudnessDb, 0.01f)
        assertEquals(2, blended.pitchClass)
    }

    @Test fun `energy rises with loudness, tempo and density`() {
        val ballad = AudioFlow.energy(profile(bpm = 72f, loudness = -22f, brightness = 1300f, onsets = 1f))
        val banger = AudioFlow.energy(profile(bpm = 150f, loudness = -6f, brightness = 4000f, onsets = 6f))
        assertTrue(ballad < 0.3f && banger > 0.8f)
    }
}
