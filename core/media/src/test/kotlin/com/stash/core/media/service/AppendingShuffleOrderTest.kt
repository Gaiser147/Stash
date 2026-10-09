package com.stash.core.media.service

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class AppendingShuffleOrderTest {

    private fun AppendingShuffleOrder.walk(): List<Int> {
        val out = mutableListOf<Int>()
        var i = firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = getNextIndex(i) }
        return out
    }

    @Test fun `a fresh queue is a full permutation`() {
        val order = AppendingShuffleOrder(0, Random(1)).cloneAndInsert(0, 10) as AppendingShuffleOrder
        assertThat(order.walk()).containsExactlyElementsIn(0 until 10)
        assertThat(order.length).isEqualTo(10)
    }

    @Test fun `appended songs always come after everything already queued`() {
        val base = AppendingShuffleOrder(0, Random(7)).cloneAndInsert(0, 6) as AppendingShuffleOrder
        val before = base.walk()
        val grown = base.cloneAndInsert(6, 4) as AppendingShuffleOrder
        val walk = grown.walk()
        assertThat(walk.take(6)).isEqualTo(before)
        assertThat(walk.drop(6)).containsExactly(6, 7, 8, 9)
    }

    @Test fun `inserting in the middle shifts later indices and still appends the new ones`() {
        val base = AppendingShuffleOrder(0, Random(3)).cloneAndInsert(0, 4) as AppendingShuffleOrder
        val before = base.walk()
        val grown = base.cloneAndInsert(2, 1) as AppendingShuffleOrder
        assertThat(grown.walk().take(4)).isEqualTo(before.map { if (it >= 2) it + 1 else it })
        assertThat(grown.walk().last()).isEqualTo(2)
    }

    @Test fun `removing keeps the remaining order and renumbers`() {
        val base = AppendingShuffleOrder(0, Random(5)).cloneAndInsert(0, 5) as AppendingShuffleOrder
        val before = base.walk()
        val removed = base.cloneAndRemove(1, 3) as AppendingShuffleOrder
        val expected = before.filter { it !in 1 until 3 }.map { if (it >= 3) it - 2 else it }
        assertThat(removed.walk()).isEqualTo(expected)
        assertThat(removed.cloneAndClear().length).isEqualTo(0)
    }

    @Test fun `previous walks backwards and the ends report unset`() {
        val order = AppendingShuffleOrder(0, Random(9)).cloneAndInsert(0, 3) as AppendingShuffleOrder
        val walk = order.walk()
        assertThat(order.getPreviousIndex(walk[1])).isEqualTo(walk[0])
        assertThat(order.getPreviousIndex(walk[0])).isEqualTo(C.INDEX_UNSET)
        assertThat(order.lastIndex).isEqualTo(walk.last())
    }
}
