package com.stash.core.data.autoplay

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How willing the user is to hear new music, learned from how they react to
 * autoplay discoveries: a Beta(alpha, beta) belief over "a discovery gets
 * listened to" (Thompson sampling, a two-armed bandit between familiar and
 * new).
 *
 *  - A discovery played to the end → [recordSuccess] (alpha + 1).
 *  - A discovery skipped in the first seconds → [recordFailure] (beta + 1).
 *
 * The share of discovery slots in a batch is `MAX_SHARE × p` with `p` drawn
 * from the belief, so the uniform prior Beta(1, 1) starts at a 25 % mean
 * share, a user who keeps finishing discoveries drifts towards 50 %, and a
 * user who skips every one of them quickly drops to a few percent while still
 * being offered the occasional new song (exploration never fully stops).
 *
 * The long-term belief is persisted by [AutoplayPreference]; it's decayed by
 * [DECAY] on every update and capped at [MAX_EVIDENCE] so it keeps adapting
 * when taste changes instead of freezing after a few hundred songs.
 */
data class DiscoveryAcceptance(
    val alpha: Float = PRIOR,
    val beta: Float = PRIOR,
) {
    val mean: Float get() = alpha / (alpha + beta)

    fun recordSuccess(): DiscoveryAcceptance = decayed().let { it.copy(alpha = it.alpha + 1f) }

    fun recordFailure(): DiscoveryAcceptance = decayed().let { it.copy(beta = it.beta + 1f) }

    /** Combines a long-term belief with this session's evidence. */
    operator fun plus(session: DiscoveryAcceptance): DiscoveryAcceptance =
        DiscoveryAcceptance(alpha + session.alpha - PRIOR, beta + session.beta - PRIOR)

    /** Thompson draw of the discovery share for the next batch, in [0, MAX_SHARE]. */
    fun sampleShare(random: Random): Float =
        (MAX_SHARE * sampleBeta(alpha, beta, random)).coerceIn(0f, MAX_SHARE)

    private fun decayed(): DiscoveryAcceptance {
        var a = PRIOR + (alpha - PRIOR) * DECAY
        var b = PRIOR + (beta - PRIOR) * DECAY
        val total = a + b
        if (total > MAX_EVIDENCE) {
            val f = MAX_EVIDENCE / total
            a *= f; b *= f
        }
        return DiscoveryAcceptance(a.coerceAtLeast(MIN_PARAM), b.coerceAtLeast(MIN_PARAM))
    }

    companion object {
        const val PRIOR = 1f
        const val MAX_SHARE = 0.5f
        const val DECAY = 0.98f
        const val MAX_EVIDENCE = 60f
        private const val MIN_PARAM = 0.1f

        /** Beta(a, b) sample via two Gamma draws. */
        internal fun sampleBeta(a: Float, b: Float, random: Random): Float {
            val x = sampleGamma(a.toDouble(), random)
            val y = sampleGamma(b.toDouble(), random)
            val sum = x + y
            return if (sum <= 0.0) 0.5f else (x / sum).toFloat()
        }

        /** Marsaglia–Tsang Gamma(shape, 1); boosted for shape < 1. */
        private fun sampleGamma(shape: Double, random: Random): Double {
            if (shape < 1.0) {
                val u = random.nextDouble(1e-12, 1.0)
                return sampleGamma(shape + 1.0, random) * u.pow(1.0 / shape)
            }
            val d = shape - 1.0 / 3.0
            val c = 1.0 / sqrt(9.0 * d)
            while (true) {
                var x: Double
                var v: Double
                do {
                    x = gaussian(random)
                    v = 1.0 + c * x
                } while (v <= 0.0)
                v = v * v * v
                val u = random.nextDouble(1e-12, 1.0)
                if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
                if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
            }
        }

        /** Box–Muller standard normal. */
        private fun gaussian(random: Random): Double {
            val u1 = random.nextDouble(1e-12, 1.0)
            val u2 = random.nextDouble()
            return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        }
    }
}
