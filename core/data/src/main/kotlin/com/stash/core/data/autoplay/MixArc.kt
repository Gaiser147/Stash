package com.stash.core.data.autoplay

import kotlin.math.abs

/**
 * Orders a finished mix like a DJ set: ease in, build to a peak around two
 * thirds, then come down a little. Energy comes from the server-side audio
 * analysis ([AudioFlow.energy]); only the ranking of energies matters, so it
 * works the same for a quiet and a loud library. Pure, deterministic.
 *
 * The opener stays first (it was picked as a familiar song on purpose), the
 * artist spread of [AutoplayRanker.ARTIST_GAP] is kept whenever possible,
 * and a mix where fewer than [MIN_ANALYSED_SHARE] of the songs are analysed
 * is returned unchanged rather than half-sorted.
 */
object MixArc {

    const val MIN_ANALYSED_SHARE = 0.6f

    /** Target energy percentile at position [i] of [n]: 0.3 → 0.9 at 65 % → 0.6. */
    fun target(i: Int, n: Int): Float {
        if (n <= 1) return 0.5f
        val x = i.toFloat() / (n - 1)
        return if (x <= PEAK_AT) 0.3f + (0.9f - 0.3f) * (x / PEAK_AT)
        else 0.9f - (0.9f - 0.6f) * ((x - PEAK_AT) / (1f - PEAK_AT))
    }

    fun <T> arrange(items: List<T>, energyOf: (T) -> Float?, artistOf: (T) -> String): List<T> {
        if (items.size < 4) return items
        val energies = items.map(energyOf)
        val analysed = energies.count { it != null }
        if (analysed < items.size * MIN_ANALYSED_SHARE) return items

        // Rank percentile of each song's energy; unanalysed songs sit in the middle.
        val order = energies.withIndex().filter { it.value != null }.sortedBy { it.value }.map { it.index }
        val pct = FloatArray(items.size) { 0.5f }
        order.forEachIndexed { rank, idx -> pct[idx] = if (order.size == 1) 0.5f else rank.toFloat() / (order.size - 1) }

        val remaining = (1 until items.size).toMutableList()
        val out = arrayListOf(0)
        while (remaining.isNotEmpty()) {
            val want = target(out.size, items.size)
            val recent = out.takeLast(AutoplayRanker.ARTIST_GAP - 1).map { artistOf(items[it]) }
            val previous = recent.last()
            val left = remaining.groupingBy { artistOf(items[it]) }.eachCount()
            val (crowded, crowdedLeft) = left.maxBy { it.value }.toPair()
            // An artist holding half of what's left must take every free slot,
            // or its songs end up back-to-back; otherwise keep the full artist
            // gap, else at least avoid an immediate repeat.
            val pool = (if (crowdedLeft * 2 >= remaining.size && crowded != previous) {
                remaining.filter { artistOf(items[it]) == crowded }
            } else {
                remaining.filter { artistOf(items[it]) !in recent }
            })
                .ifEmpty { remaining.filter { artistOf(items[it]) != previous } }
                .ifEmpty { remaining }
            // Artists with many songs left go a little earlier, so they don't
            // pile up at the end where only back-to-back repeats remain.
            val next = pool.minBy { abs(pct[it] - want) - SPREAD_BIAS * (left.getValue(artistOf(items[it])) - 1) }
            out += next
            remaining.remove(next)
        }
        return out.map { items[it] }
    }

    private const val PEAK_AT = 0.65f
    private const val SPREAD_BIAS = 0.08f
}
