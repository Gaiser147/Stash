package com.stash.core.data.stats

import com.stash.core.data.autoplay.AudioFlow
import com.stash.core.data.autoplay.TimeOfDayEnergy
import com.stash.core.data.autoplay.TimeOfDayEnergy.toProfile
import com.stash.core.data.db.dao.HeardSound
import com.stash.core.data.db.dao.PlayRow
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure aggregation for the statistics page: listening time per day, hour and
 * weekday × hour, and the distribution of the sound that was heard. Time zone
 * injected so the tests don't depend on the machine's.
 */
object StatsAggregator {

    /** Plays recorded before v35 have no depth; they count as this much (capped at the song length). */
    const val LEGACY_PLAY_MS = 4L * 60 * 1000

    /** How long a play was heard, best estimate. */
    fun listenedMs(r: PlayRow): Long {
        r.listenedMs?.let { return it.coerceAtLeast(0) }
        val length = (r.durationMs ?: r.trackDurationMs).takeIf { it > 0 }
        return if (length == null) LEGACY_PLAY_MS else minOf(length, LEGACY_PLAY_MS)
    }

    /** Listening time per calendar day for the last [days] days, oldest first (index days-1 = today). */
    fun perDay(rows: List<PlayRow>, days: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): LongArray {
        val out = LongArray(days)
        val today = dayIndex(now, zone)
        for (r in rows) {
            val back = today - dayIndex(r.startedAt, zone)
            if (back in 0 until days) out[(days - 1 - back).toInt()] += listenedMs(r)
        }
        return out
    }

    /** Listening time per hour of day (0–23). */
    fun perHour(rows: List<PlayRow>, zone: TimeZone = TimeZone.getDefault()): LongArray {
        val out = LongArray(24)
        val cal = Calendar.getInstance(zone)
        for (r in rows) {
            cal.timeInMillis = r.startedAt
            out[cal.get(Calendar.HOUR_OF_DAY)] += listenedMs(r)
        }
        return out
    }

    /** Listening time per weekday (0 = Monday) × hour. */
    fun weekdayHour(rows: List<PlayRow>, zone: TimeZone = TimeZone.getDefault()): Array<LongArray> {
        val out = Array(7) { LongArray(24) }
        val cal = Calendar.getInstance(zone)
        for (r in rows) {
            cal.timeInMillis = r.startedAt
            val wd = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            out[wd][cal.get(Calendar.HOUR_OF_DAY)] += listenedMs(r)
        }
        return out
    }

    /** Share of plays per bucket of [edges] (n edges → n+1 buckets). */
    fun histogram(values: List<Float>, edges: List<Float>): List<Float> {
        if (values.isEmpty()) return List(edges.size + 1) { 0f }
        val counts = IntArray(edges.size + 1)
        for (v in values) counts[edges.indexOfFirst { v < it }.let { if (it < 0) edges.size else it }]++
        return counts.map { it.toFloat() / values.size }
    }

    data class Sound(
        val plays: Int,
        /** Share of plays per tempo bucket: < 90, 90–110, 110–130, 130–150, ≥ 150 BPM. */
        val tempo: List<Float>,
        /** Share of plays per energy fifth (calm … intense). */
        val energy: List<Float>,
        val minorShare: Float,
        val medianBpm: Float?,
        /** Mean energy per three-hour window (null = too few plays). */
        val energyByTimeOfDay: List<Float?>,
    )

    val TEMPO_EDGES = listOf(90f, 110f, 130f, 150f)
    val ENERGY_EDGES = listOf(0.2f, 0.4f, 0.6f, 0.8f)

    fun sound(rows: List<HeardSound>, zone: TimeZone = TimeZone.getDefault()): Sound {
        val withTempo = rows.filter { it.bpm > 0f && it.beatConfidence >= 0.15f }.map { it.bpm }
        val energies = rows.map { AudioFlow.energy(it.toProfile()) }
        val keyed = rows.filter { it.keyStrength >= 0.3f }
        return Sound(
            plays = rows.size,
            tempo = histogram(withTempo, TEMPO_EDGES),
            energy = histogram(energies, ENERGY_EDGES),
            minorShare = if (keyed.isEmpty()) 0f else keyed.count { it.minor }.toFloat() / keyed.size,
            medianBpm = withTempo.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
            energyByTimeOfDay = TimeOfDayEnergy.profile(rows, zone),
        )
    }

    private fun dayIndex(ms: Long, zone: TimeZone): Long = (ms + zone.getOffset(ms)) / 86_400_000L
}
