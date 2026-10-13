package com.stash.core.data.autoplay

import com.stash.core.data.db.dao.HeardSound
import java.util.Calendar
import java.util.TimeZone

/**
 * What the user tends to listen to at each time of day, as the mean energy
 * ([AudioFlow.energy]) of the analysed songs they heard in each three-hour
 * window. A mix started with nothing to go on (no request, no analysed seed)
 * aims at the window's typical energy instead of a random one — calmer late
 * at night if that is what the history says. Pure; time zone injected.
 */
object TimeOfDayEnergy {

    const val BUCKET_HOURS = 3
    const val BUCKETS = 24 / BUCKET_HOURS

    /** Fewer plays than this in a window and it isn't trusted. */
    const val MIN_SAMPLES = 15

    fun bucketOf(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return cal.get(Calendar.HOUR_OF_DAY) / BUCKET_HOURS
    }

    /** Mean energy per bucket; null where there are too few plays. */
    fun profile(rows: List<HeardSound>, zone: TimeZone = TimeZone.getDefault()): List<Float?> {
        val sum = FloatArray(BUCKETS)
        val n = IntArray(BUCKETS)
        for (r in rows) {
            val b = bucketOf(r.startedAt, zone)
            sum[b] += AudioFlow.energy(r.toProfile())
            n[b]++
        }
        return (0 until BUCKETS).map { if (n[it] >= MIN_SAMPLES) sum[it] / n[it] else null }
    }

    fun HeardSound.toProfile() = AudioProfile(
        bpm = bpm,
        beatConfidence = beatConfidence,
        loudnessDb = loudnessDb,
        brightnessHz = brightnessHz,
        onsetRate = onsetRate,
        pitchClass = 0,
        minor = minor,
        keyStrength = keyStrength,
    )
}
