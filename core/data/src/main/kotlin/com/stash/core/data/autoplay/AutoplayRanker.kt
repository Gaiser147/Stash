package com.stash.core.data.autoplay

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Pure ranking + assembly for autoplay. No I/O, randomness injected, so every
 * rule is unit-testable and a fixed seed gives a fixed queue.
 *
 * ## Scoring
 * ```
 * score = 0.31·sessionSim + 0.22·affinity + 0.18·transition
 *       + 0.09·completion + 0.08·lastFmMatch + 0.12·flow
 *       − skipPenalty − recentPenalty
 * ```
 * `sessionSim` dominates on purpose: autoplay should continue the mood of
 * what is playing *now*, with long-term taste as the tie-breaker. `flow`
 * adds how the song *sounds* next to the last one (tempo, loudness, key;
 * [AudioFlow]); songs without an analysis get the neutral 0.5.
 *
 * ## Sampling
 * Picks are drawn by softmax sampling (Gumbel-top-k) at [TEMPERATURE]
 * instead of a strict sort — the best songs are still the most likely, but
 * the same seed doesn't produce the same continuation every time.
 *
 * ## Assembly rules
 *  - An artist may not reappear within [ARTIST_GAP] songs (seeded with the
 *    songs that just played, so the first pick doesn't repeat the artist of
 *    the last queue track). Relaxed only when nothing else is left.
 *  - Each slot is a discovery with probability `discoveryShare`, but never
 *    two discoveries back-to-back, and the first slot of a fresh session is
 *    always familiar ("first something you know, then something new").
 *  - An empty side falls back to the other side, so a missing Last.fm or an
 *    offline phone still gets a full batch from the library.
 */
object AutoplayRanker {

    const val W_SESSION = 0.31f
    const val W_AFFINITY = 0.22f
    const val W_TRANSITION = 0.18f
    const val W_COMPLETION = 0.09f
    const val W_LASTFM = 0.08f
    const val W_FLOW = 0.12f

    /** Softmax temperature. Lower = greedier. Scores span roughly 0..1. */
    const val TEMPERATURE = 0.08f

    /** Minimum distance (in songs) between two songs by the same artist. */
    const val ARTIST_GAP = 3

    fun score(c: AutoplayCandidate): Float =
        W_SESSION * c.sessionSim +
            W_AFFINITY * c.affinity +
            W_TRANSITION * c.transition +
            W_COMPLETION * c.completion +
            W_LASTFM * c.lastFmMatch +
            W_FLOW * c.flow -
            c.skipPenalty -
            c.recentPenalty

    /**
     * Orders [candidates] by a Gumbel-perturbed score: equivalent to drawing
     * without replacement from `softmax(score / temperature)`.
     */
    fun sampleOrder(
        candidates: List<AutoplayCandidate>,
        random: Random,
        temperature: Float = TEMPERATURE,
    ): List<AutoplayCandidate> {
        if (temperature <= 0f) return candidates.sortedByDescending(::score)
        return candidates
            .map { it to (score(it) / temperature + gumbel(random)) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Assembles the next [size] songs.
     *
     * @param recentArtists lowercased artists of the songs that just played,
     *   oldest first; seeds the artist-gap rule.
     * @param discoveryShare probability that a slot is a discovery, in [0, 1].
     * @param openFamiliar force slot 0 to be a library song.
     */
    fun assemble(
        library: List<AutoplayCandidate>,
        discovery: List<AutoplayCandidate>,
        size: Int,
        discoveryShare: Float,
        recentArtists: List<String>,
        random: Random,
        openFamiliar: Boolean = false,
        temperature: Float = TEMPERATURE,
    ): List<AutoplayCandidate> {
        if (size <= 0) return emptyList()
        val lib = sampleOrder(library, random, temperature).toMutableList()
        val disc = sampleOrder(discovery, random, temperature).toMutableList()
        val share = discoveryShare.coerceIn(0f, 1f)

        val out = ArrayList<AutoplayCandidate>(size)
        val usedKeys = HashSet<String>()
        val window = ArrayDeque(recentArtists.takeLast(ARTIST_GAP - 1))
        var previousWasDiscovery = false

        while (out.size < size && (lib.isNotEmpty() || disc.isNotEmpty())) {
            val wantDiscovery = !(openFamiliar && out.isEmpty()) &&
                !previousWasDiscovery &&
                share > 0f &&
                random.nextFloat() < share
            val primary = if (wantDiscovery) disc else lib
            val fallback = if (wantDiscovery) lib else disc

            val pick = takeSpread(primary, window, usedKeys)
                ?: takeSpread(fallback, window, usedKeys)
                ?: takeAny(primary, usedKeys)
                ?: takeAny(fallback, usedKeys)
                ?: break

            out += pick
            usedKeys += pick.key
            previousWasDiscovery = pick.origin == AutoplayOrigin.DISCOVERY
            window.addLast(pick.artistKey)
            while (window.size > ARTIST_GAP - 1) window.removeFirst()
        }
        return out
    }

    /** First candidate whose artist isn't in the gap [window]. */
    private fun takeSpread(
        list: MutableList<AutoplayCandidate>,
        window: Collection<String>,
        usedKeys: Set<String>,
    ): AutoplayCandidate? {
        list.removeAll { it.key in usedKeys }
        val idx = list.indexOfFirst { it.artistKey !in window }
        return if (idx >= 0) list.removeAt(idx) else null
    }

    /** Relaxed pick when every remaining candidate violates the artist gap. */
    private fun takeAny(
        list: MutableList<AutoplayCandidate>,
        usedKeys: Set<String>,
    ): AutoplayCandidate? {
        list.removeAll { it.key in usedKeys }
        return list.removeFirstOrNull()
    }

    private fun gumbel(random: Random): Float {
        // u in (0, 1) — both ends excluded so ln never sees 0.
        val u = random.nextDouble(1e-12, 1.0 - 1e-12)
        return (-ln(-ln(u))).toFloat()
    }

    /** Saturating map of an unbounded count into [0, 1): 0 → 0, [halfAt] → 0.5. */
    internal fun squash(x: Float, halfAt: Float): Float =
        if (x <= 0f) 0f else (1f - exp(-x * ln(2f) / halfAt))
}
