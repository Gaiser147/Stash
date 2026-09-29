package com.stash.core.data.autoplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiscoveryAcceptanceTest {

    private fun meanShare(a: DiscoveryAcceptance, draws: Int = 4000): Float {
        val r = Random(7)
        return (0 until draws).map { a.sampleShare(r) }.average().toFloat()
    }

    @Test fun `uniform prior starts around a quarter`() {
        assertEquals(0.25f, meanShare(DiscoveryAcceptance()), 0.02f)
    }

    @Test fun `finishing discoveries raises the share`() {
        var a = DiscoveryAcceptance()
        repeat(10) { a = a.recordSuccess() }
        assertTrue(a.mean > 0.8f)
        assertTrue(meanShare(a) > 0.38f)
    }

    @Test fun `skipping discoveries lowers the share but never to zero`() {
        var a = DiscoveryAcceptance()
        repeat(10) { a = a.recordFailure() }
        val share = meanShare(a)
        assertTrue("share $share", share < 0.08f)
        assertTrue("exploration must continue", share > 0f)
    }

    @Test fun `samples stay within bounds`() {
        val r = Random(1)
        listOf(DiscoveryAcceptance(0.1f, 50f), DiscoveryAcceptance(50f, 0.1f), DiscoveryAcceptance()).forEach { a ->
            repeat(1000) {
                val s = a.sampleShare(r)
                assertTrue(s in 0f..DiscoveryAcceptance.MAX_SHARE)
            }
        }
    }

    @Test fun `evidence is capped so taste changes are picked up`() {
        var a = DiscoveryAcceptance()
        repeat(500) { a = a.recordSuccess() }
        assertTrue(a.alpha + a.beta <= DiscoveryAcceptance.MAX_EVIDENCE + 1.01f)
        // After a long streak of successes, a run of skips still moves the needle.
        repeat(15) { a = a.recordFailure() }
        assertTrue("mean ${a.mean}", a.mean < 0.8f)
    }

    @Test fun `session evidence adds to the long-term belief`() {
        val longTerm = DiscoveryAcceptance(5f, 1f)
        val session = DiscoveryAcceptance().recordFailure().recordFailure()
        val combined = longTerm + session
        assertTrue(combined.beta > longTerm.beta)
        assertEquals(longTerm.alpha, combined.alpha, 0.1f)
    }
}
