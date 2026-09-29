package com.stash.core.data.mix

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

/**
 * Per-track taste signals shared by every local recommender: the Stash Mix
 * materializer ([MixGenerator]) and the queue-continuation engine
 * ([com.stash.core.data.autoplay.AutoplayEngine]). Extracted from
 * [MixGenerator] unchanged so both rank against the same notion of
 * "what this user likes".
 *
 * Every map is keyed by track id; a missing entry means "no signal" and the
 * caller chooses the neutral default.
 */
@Singleton
class TrackSignals @Inject constructor(
    private val listeningEventDao: ListeningEventDao,
    private val trackTagDao: TrackTagDao,
    private val trackSkipEventDao: TrackSkipEventDao,
) {

    companion object {
        /** Recency window for affinity. Decay half-life is what controls "current"-ness. */
        const val AFFINITY_WINDOW_MS = 180L * 24 * 60 * 60 * 1000

        /** Half-life of the affinity exponential decay, in milliseconds (30 days). */
        const val AFFINITY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

        /** Window for skip-rate computation. Shorter than affinity — skips age fast. */
        const val SKIP_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        /** Window for completion-rate computation. */
        const val COMPLETION_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

        const val SKIP_PENALTY_HEAVY = 0.6f       // when skip-rate >= ramp
        const val SKIP_PENALTY_RAMP  = 0.6f       // skip-rate above which heavy penalty kicks in

        /**
         * Scalar applied to the Last.fm-user-playcount term INSIDE
         * [affinity]. Intentionally lower than parity with local plays —
         * Last.fm playcount counts every scrobble across every service the
         * user ever connected, so a single track with 200 lifetime LFM plays
         * shouldn't outweigh a 30-play in-Stash track from this month.
         */
        const val LFM_PLAYCOUNT_W = 0.3f

        /** Sentinel tag rows written for tracks Last.fm couldn't tag. */
        const val UNTAGGABLE = "__untaggable__"
    }

    /**
     * Build per-track affinity scores in the range [0, 1]. Combines two
     * signals:
     *  - In-Stash plays in the last 180 days, log-normalized by the
     *    library's max count and exponentially decayed by recency
     *    (30-day half-life from [AFFINITY_HALF_LIFE_MS]).
     *  - Last.fm cross-source playcount (only present after Last.fm
     *    track-info enrichment) scaled by [LFM_PLAYCOUNT_W].
     *
     * Tracks the user has neither played in-Stash nor scrobbled to
     * Last.fm get a zero entry (omitted from the map).
     */
    suspend fun affinity(pool: List<TrackEntity>): Map<Long, Float> {
        val now = System.currentTimeMillis()
        val since = now - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)

        if (rows.isEmpty() && pool.none { (it.lastfmUserPlaycount ?: 0) > 0 }) {
            return emptyMap()
        }

        val maxPlays = (rows.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
        val maxLfmPlays = pool.maxOfOrNull { it.lastfmUserPlaycount ?: 0 }?.coerceAtLeast(1) ?: 1

        val byId = rows.associateBy { it.trackId }
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            val row = byId[track.id]
            // In-Stash plays with exponential decay
            val localTerm = if (row != null) {
                val logNorm = (ln(1f + row.plays.toFloat()) /
                    ln(1f + maxPlays.toFloat())).coerceIn(0f, 1f)
                val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
                val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
                logNorm * decay
            } else 0f
            // Last.fm cross-source playcount (only present after enrichment)
            val lfmTerm = track.lastfmUserPlaycount?.let { lpc ->
                (ln(1f + lpc.toFloat()) /
                    ln(1f + maxLfmPlays.toFloat())).coerceIn(0f, 1f) * LFM_PLAYCOUNT_W
            } ?: 0f
            val combined = (localTerm + lfmTerm).coerceIn(0f, 1f)
            if (combined > 0f) result[track.id] = combined
        }
        return result
    }

    /**
     * L2-normalized user tag-affinity vector: each played track's tag vector
     * weighted by its (decayed) play weight and summed.
     */
    suspend fun userTagVector(): Map<String, Float> {
        val now = System.currentTimeMillis()
        val since = now - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)
        if (rows.isEmpty()) return emptyMap()

        val plays = rows.map { row ->
            val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
            val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
            val weight = ln(1f + row.plays.toFloat()) * decay
            UserTagAffinity.PlayWithTags(weight = weight, tags = tagsOf(row.trackId))
        }
        return UserTagAffinity.compute(plays)
    }

    /** Per-candidate cosine similarity against [vector] (user or session). */
    suspend fun tagCosine(
        pool: List<TrackEntity>,
        vector: Map<String, Float>,
    ): Map<Long, Float> {
        if (vector.isEmpty()) return emptyMap()
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            val tags = tagsOf(track.id)
            if (tags.isEmpty()) continue
            result[track.id] = UserTagAffinity.cosine(tags, vector)
        }
        return result
    }

    /** A track's tag-weight vector, lowercase keys, sentinel rows dropped. */
    suspend fun tagsOf(trackId: Long): Map<String, Float> =
        trackTagDao.getByTrack(trackId)
            .filter { it.tag != UNTAGGABLE }
            .associate { it.tag.lowercase() to it.weight }

    /**
     * Per-track completion rate over the last 60 days. Tracks with no
     * listening history get omitted (callers default to 0.5 neutral so
     * brand-new tracks aren't penalized).
     */
    suspend fun completion(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - COMPLETION_WINDOW_MS
        val ids = pool.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = listeningEventDao.getCompletionStatsSince(ids, since)
        return rows.associate {
            it.trackId to (it.completed.toFloat() / it.total.coerceAtLeast(1).toFloat())
        }
    }

    /**
     * Per-track skip-rate penalty over the last 14 days. Tracks with fewer
     * than 3 total encounters are excluded (not enough signal). Skip-rate
     * >= [SKIP_PENALTY_RAMP] gets the shadow-block-grade [SKIP_PENALTY_HEAVY]
     * penalty; skip-rate between 0.4 and the ramp gets a linear ramp so a
     * track on its way out of rotation degrades smoothly.
     */
    suspend fun skipPenalty(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - SKIP_WINDOW_MS
        val ids = pool.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = trackSkipEventDao.getSkipStatsSince(ids, since)
        return rows.mapNotNull { row ->
            val total = row.skips + row.plays
            if (total < 3) return@mapNotNull null  // not enough data
            val rate = row.skips.toFloat() / total
            val penalty = when {
                rate >= SKIP_PENALTY_RAMP -> SKIP_PENALTY_HEAVY              // shadow-block-ish
                rate >= 0.4f -> (rate - 0.4f) / 0.2f * 0.4f                   // linear ramp
                else -> 0f
            }
            if (penalty > 0f) row.trackId to penalty else null
        }.toMap()
    }
}
