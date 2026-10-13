package com.stash.core.data.autoplay

import com.stash.core.data.mix.MoodTagMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The band of sound a session or a mix request should stay in: energy
 * (see [AudioFlow.energy]) and optionally tempo.
 *
 * Autoplay used to only *prefer* songs that sound like the last one (`flow`),
 * so one strong neighbour could pull a calm session onto a loud track. The
 * corridor turns that into a boundary: analysed songs outside it are pushed
 * far down (see [AutoplayRanker]); songs without an analysis are neither
 * rewarded nor punished.
 *
 * @property energyCenter target energy in [0, 1].
 * @property energyWidth half-width of the band where the fit stays 1.
 * @property bpm tempo centre, or null for "any tempo". Half/double time
 *   count as the same tempo ([AudioFlow.tempoDistance]).
 * @property tempoWidth half-width of the tempo band in log space
 *   (`ln(1.12)` ≈ ±12 %).
 * @property bpmMin / bpmMax hard tempo limits from a request ("slow" =
 *   under 100 BPM), no half/double-time folding.
 */
data class MoodCorridor(
    val energyCenter: Float,
    val energyWidth: Float = DEFAULT_ENERGY_WIDTH,
    val bpm: Float? = null,
    val tempoWidth: Float = DEFAULT_TEMPO_WIDTH,
    val bpmMin: Float? = null,
    val bpmMax: Float? = null,
) {

    /** 1 inside the band, falling smoothly to 0 outside it. */
    fun fit(p: AudioProfile): Float {
        val e = abs(AudioFlow.energy(p) - energyCenter)
        val energyFit = if (e <= energyWidth) 1f else gauss(e - energyWidth, FALLOFF_ENERGY)
        var tempoFit = 1f
        if (p.bpm > 0f && p.beatConfidence >= MIN_TEMPO_CONFIDENCE) {
            if (bpm != null && bpm > 0f) {
                val d = AudioFlow.tempoDistance(p.bpm, bpm)
                if (d > tempoWidth) tempoFit = gauss(d - tempoWidth, FALLOFF_TEMPO)
            }
            bpmMin?.let { if (p.bpm < it) tempoFit = minOf(tempoFit, gauss(ln(it / p.bpm), FALLOFF_TEMPO)) }
            bpmMax?.let { if (p.bpm > it) tempoFit = minOf(tempoFit, gauss(ln(p.bpm / it), FALLOFF_TEMPO)) }
        }
        // Energy is the mood; tempo a softer constraint unless a request set limits.
        val tempoWeight = if (bpmMin != null || bpmMax != null) 1f else 0.5f
        return energyFit * (1f - tempoWeight + tempoWeight * tempoFit)
    }

    /** The same corridor with every band [factor] times wider. */
    fun widened(factor: Float) = copy(
        energyWidth = energyWidth * factor,
        tempoWidth = tempoWidth * factor,
        bpmMin = bpmMin?.let { it / factor },
        bpmMax = bpmMax?.let { it * factor },
    )

    override fun toString(): String =
        "energy=%.2f±%.2f%s%s".format(
            energyCenter, energyWidth,
            bpm?.let { " bpm=%.0f".format(it) } ?: "",
            if (bpmMin != null || bpmMax != null) " bpm[${bpmMin?.toInt() ?: "-"}..${bpmMax?.toInt() ?: "-"}]" else "",
        )

    companion object {
        const val DEFAULT_ENERGY_WIDTH = 0.18f
        val DEFAULT_TEMPO_WIDTH = ln(1.12f)
        private const val FALLOFF_ENERGY = 0.10f
        private const val FALLOFF_TEMPO = 0.10f
        private const val MIN_TEMPO_CONFIDENCE = 0.15f

        /** Below this many in-corridor candidates the band is widened. */
        const val MIN_INSIDE = 12

        /** Corridor around where the session already is. */
        fun around(target: AudioProfile): MoodCorridor = MoodCorridor(
            energyCenter = AudioFlow.energy(target),
            bpm = target.bpm.takeIf { it > 0f && target.beatConfidence >= MIN_TEMPO_CONFIDENCE },
        )

        /**
         * Widens [base] until at least [MIN_INSIDE] of [profiles] fit it
         * (fit ≥ 0.5), at most three steps. A small or unusual library must
         * not end up with an empty mix because the band was too strict.
         */
        fun fitted(base: MoodCorridor, profiles: Collection<AudioProfile>): MoodCorridor {
            var c = base
            repeat(3) {
                if (profiles.count { c.fit(it) >= 0.5f } >= minOf(MIN_INSIDE, profiles.size)) return c
                c = c.widened(1.5f)
            }
            return c
        }

        private fun gauss(x: Float, width: Float): Float = exp(-(x / width) * (x / width))
    }
}

/**
 * Mood from tags: how much of each [MoodTagMap] mood a song's (or session's)
 * Last.fm tags carry, and whether two moods clash. Lets songs without an
 * audio analysis — every discovery — be checked against the session's mood.
 */
object MoodSpace {

    /** Neutral [fit] when either side carries no mood tags. */
    const val NEUTRAL = 0.5f

    /** Moods that don't belong in the same session. Symmetric. */
    private val CLASHES: Set<Pair<String, String>> = setOf(
        "chill" to "party",
        "chill" to "energetic",
        "focus" to "party",
        "melancholy" to "party",
        "melancholy" to "energetic",
        "romantic" to "energetic",
    ).flatMap { listOf(it, it.second to it.first) }.toSet()

    /** tag → moods containing it (lowercased). */
    private val TAG_TO_MOODS: Map<String, List<String>> = MoodTagMap.MAP
        .flatMap { (mood, tags) -> tags.map { it.lowercase() to mood } }
        .groupBy({ it.first }, { it.second })

    /** Mood weights from weighted tags, L2-normalised; empty when no tag is a mood tag. */
    fun moodsOf(tags: Map<String, Float>): Map<String, Float> {
        val acc = HashMap<String, Float>()
        for ((tag, w) in tags) {
            if (w <= 0f) continue
            TAG_TO_MOODS[tag.trim().lowercase()]?.forEach { acc[it] = (acc[it] ?: 0f) + w }
        }
        val mag = sqrt(acc.values.sumOf { (it * it).toDouble() }).toFloat()
        return if (mag <= 0f) emptyMap() else acc.mapValues { it.value / mag }
    }

    /**
     * How well [candidate] moods fit the [session] moods, in [0, 1]:
     * 0.5 + half the agreement (cosine) − the clashing mass. Neutral when
     * either is unknown.
     */
    fun fit(session: Map<String, Float>, candidate: Map<String, Float>): Float {
        if (session.isEmpty() || candidate.isEmpty()) return NEUTRAL
        val agree = session.entries.sumOf { (m, w) -> (w * (candidate[m] ?: 0f)).toDouble() }.toFloat()
        var clash = 0f
        for ((a, wa) in session) for ((b, wb) in candidate) if ((a to b) in CLASHES) clash += wa * wb
        return (NEUTRAL + 0.5f * agree - clash).coerceIn(0f, 1f)
    }

    /** True when the candidate's dominant mood clashes with the session's. */
    fun clashes(session: Map<String, Float>, candidate: Map<String, Float>): Boolean {
        val s = session.maxByOrNull { it.value }?.key ?: return false
        val c = candidate.maxByOrNull { it.value }?.key ?: return false
        return (s to c) in CLASHES
    }
}
