package com.stash.core.data.autoplay

import com.stash.core.data.db.entity.AudioFeaturesEntity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * What a song sounds like, from the server-side analysis
 * ([AudioFeaturesEntity]): tempo, loudness, brightness, busyness and key.
 */
data class AudioProfile(
    val bpm: Float,
    val beatConfidence: Float,
    val loudnessDb: Float,
    val brightnessHz: Float,
    val onsetRate: Float,
    val pitchClass: Int,
    val minor: Boolean,
    val keyStrength: Float,
) {
    companion object {
        fun of(e: AudioFeaturesEntity) = AudioProfile(
            bpm = e.bpm,
            beatConfidence = e.beatConfidence,
            loudnessDb = e.loudnessDb,
            brightnessHz = e.brightnessHz,
            onsetRate = e.onsetRate,
            pitchClass = e.pitchClass,
            minor = e.minor,
            keyStrength = e.keyStrength,
        )
    }
}

/**
 * "Does this song follow that one well?" in [0, 1], the way a DJ would judge
 * a transition: close tempo (half/double time counts as close), no jump in
 * loudness or brightness, similar busyness, and a harmonically compatible
 * key on the Camelot wheel. Pure functions; see [AutoplayRanker] for how the
 * result is weighted.
 */
object AudioFlow {

    /** Score for a candidate without analysis: neither rewarded nor punished. */
    const val NEUTRAL = 0.5f

    private const val W_TEMPO = 0.35f
    private const val W_LOUDNESS = 0.25f
    private const val W_BRIGHTNESS = 0.15f
    private const val W_KEY = 0.15f
    private const val W_ONSETS = 0.10f

    /** ~8 % tempo difference halves the tempo score. */
    private const val TEMPO_WIDTH = 0.1f
    private const val LOUDNESS_WIDTH_DB = 4f
    private const val BRIGHTNESS_WIDTH = 0.35f
    private const val ONSET_WIDTH = 0.45f

    /** Beat tracking below this confidence says little about the tempo. */
    private const val MIN_BEAT_CONFIDENCE = 0.1f

    fun similarity(a: AudioProfile, b: AudioProfile): Float {
        var total = 0f
        var weight = 0f
        fun add(w: Float, v: Float) {
            total += w * v
            weight += w
        }
        if (a.bpm > 0f && b.bpm > 0f) {
            // Tempo only counts as far as both beat trackers were sure.
            val trust = min(confidence(a), confidence(b))
            if (trust > 0f) add(W_TEMPO * trust, gauss(tempoDistance(a.bpm, b.bpm), TEMPO_WIDTH))
        }
        add(W_LOUDNESS, gauss(a.loudnessDb - b.loudnessDb, LOUDNESS_WIDTH_DB))
        if (a.brightnessHz > 0f && b.brightnessHz > 0f) {
            add(W_BRIGHTNESS, gauss(ln(a.brightnessHz / b.brightnessHz), BRIGHTNESS_WIDTH))
        }
        add(W_ONSETS, gauss(ln((a.onsetRate + 0.2f) / (b.onsetRate + 0.2f)), ONSET_WIDTH))
        val keyTrust = min(a.keyStrength, b.keyStrength).coerceIn(0f, 1f)
        if (keyTrust > 0f) add(W_KEY * keyTrust, keyCompatibility(a, b))
        return if (weight <= 0f) NEUTRAL else (total / weight).coerceIn(0f, 1f)
    }

    /**
     * Distance between two tempos in log space, taking the closest of
     * same/half/double time: 70 and 140 BPM feel the same on a dance floor.
     */
    fun tempoDistance(a: Float, b: Float): Float {
        val r = ln(a / b)
        val ln2 = ln(2f)
        return minOf(abs(r), abs(r - ln2), abs(r + ln2))
    }

    /** Camelot number 1-12 of a key. */
    fun camelotNumber(pitchClass: Int, minor: Boolean): Int {
        // Major: C=8B, G=9B (a fifth up = +1). Minor keys share the number of
        // their relative major (A minor = 8A, like C major).
        val majorRoot = if (minor) (pitchClass + 3) % 12 else pitchClass
        return ((majorRoot * 7) % 12 + 8 - 1) % 12 + 1
    }

    /**
     * 1 for the same key, 0.85 for the neighbours DJs mix into (±1 on the
     * wheel, or the relative major/minor), 0.55 for two steps or the parallel
     * key, 0.25 otherwise.
     */
    fun keyCompatibility(a: AudioProfile, b: AudioProfile): Float {
        val na = camelotNumber(a.pitchClass, a.minor)
        val nb = camelotNumber(b.pitchClass, b.minor)
        val step = ((na - nb) % 12 + 12) % 12
        val wheel = minOf(step, 12 - step)
        return when {
            wheel == 0 && a.minor == b.minor -> 1f
            wheel == 0 -> 0.85f // relative major/minor
            wheel == 1 && a.minor == b.minor -> 0.85f
            a.pitchClass == b.pitchClass -> 0.55f // parallel major/minor
            wheel == 2 && a.minor == b.minor -> 0.55f
            wheel == 1 -> 0.45f
            else -> 0.25f
        }
    }

    /**
     * Where the session is: a weighted blend of what was just heard. The key
     * of the newest song wins (keys don't average); everything else is a
     * weighted mean. Null without any profile.
     */
    fun blend(weighted: List<Pair<AudioProfile, Float>>): AudioProfile? {
        val items = weighted.filter { it.second > 0f }
        if (items.isEmpty()) return null
        val sum = items.sumOf { it.second.toDouble() }.toFloat()
        fun mean(f: (AudioProfile) -> Float) = items.sumOf { (f(it.first) * it.second).toDouble() }.toFloat() / sum
        // Tempo: mean of log tempo folded onto the first song's octave.
        val anchor = items.first().first.bpm
        val tempos = items.filter { it.first.bpm > 0f }
        val bpm = if (anchor <= 0f || tempos.isEmpty()) 0f else {
            val tSum = tempos.sumOf { it.second.toDouble() }.toFloat()
            exp(tempos.sumOf { (fold(ln(it.first.bpm / anchor)) * it.second).toDouble() }.toFloat() / tSum) * anchor
        }
        val newest = items.first().first
        return AudioProfile(
            bpm = bpm,
            beatConfidence = mean { it.beatConfidence },
            loudnessDb = mean { it.loudnessDb },
            brightnessHz = mean { it.brightnessHz },
            onsetRate = mean { it.onsetRate },
            pitchClass = newest.pitchClass,
            minor = newest.minor,
            keyStrength = newest.keyStrength,
        )
    }

    /**
     * One number for "how intense": loud, bright, busy and fast. Roughly 0
     * (quiet ballad) to 1 (loud, fast, dense); used to shape a mix's arc.
     */
    fun energy(p: AudioProfile): Float {
        val loud = ((p.loudnessDb + 30f) / 25f).coerceIn(0f, 1f)
        val bright = ((ln(p.brightnessHz.coerceAtLeast(1f)) - ln(1200f)) / (ln(4500f) - ln(1200f))).coerceIn(0f, 1f)
        val busy = (p.onsetRate / 6f).coerceIn(0f, 1f)
        val tempo = if (p.bpm > 0f) ((p.bpm - 70f) / 110f).coerceIn(0f, 1f) else 0.5f
        return 0.4f * loud + 0.2f * bright + 0.2f * busy + 0.2f * tempo
    }

    private fun confidence(p: AudioProfile): Float =
        ((p.beatConfidence - MIN_BEAT_CONFIDENCE) / (1f - MIN_BEAT_CONFIDENCE)).coerceIn(0f, 1f).let { 0.3f + 0.7f * it }

    private fun fold(logRatio: Float): Float {
        val ln2 = ln(2f)
        var r = logRatio
        while (r > ln2 / 2) r -= ln2
        while (r < -ln2 / 2) r += ln2
        return r
    }

    private fun gauss(x: Float, width: Float): Float = exp(-(x / width) * (x / width))
}
