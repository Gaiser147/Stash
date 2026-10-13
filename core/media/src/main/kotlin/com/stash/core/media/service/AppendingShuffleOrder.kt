package com.stash.core.media.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle order that puts newly added songs AFTER everything already in the
 * order (shuffled among themselves), instead of ExoPlayer's default of random
 * positions. With the default, songs autoplay appended could land before the
 * current song in play order — they showed in the queue but never played, and
 * when all of them landed there, playback simply ended.
 *
 * A fresh queue (insert into an empty order) is fully shuffled as usual.
 */
@OptIn(UnstableApi::class)
class AppendingShuffleOrder private constructor(
    private val shuffled: IntArray,
    private val random: Random,
) : ShuffleOrder {

    constructor(length: Int, random: Random = Random(System.nanoTime())) :
        this(IntArray(length) { it }.also { it.shuffle(random) }, random)

    /** Position of each index within [shuffled]. */
    private val positionOf = IntArray(shuffled.size).also { pos ->
        shuffled.forEachIndexed { p, index -> pos[index] = p }
    }

    /** The play order, as indices. */
    internal fun order(): List<Int> = shuffled.toList()

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        val p = positionOf[index] + 1
        return if (p < shuffled.size) shuffled[p] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = positionOf[index] - 1
        return if (p >= 0) shuffled[p] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.last()

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.first()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val kept = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }
        val added = IntArray(insertionCount) { insertionIndex + it }.also { it.shuffle(random) }
        return AppendingShuffleOrder((kept + added.toList()).toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = indexToExclusive - indexFrom
        val kept = shuffled.filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - removed else it }
        return AppendingShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = AppendingShuffleOrder(IntArray(0), random)
}
