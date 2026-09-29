package com.stash.core.data.mix

import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Pure materializer for a [StashMixRecipeEntity] — given the recipe and
 * read access to the library, it produces an ordered list of `TrackEntity`
 * rows that the refresh worker writes into the recipe's playlist.
 *
 * ## Scoring model (v0.9.16)
 *
 * Every candidate track is assigned a score that linearly combines:
 *
 *  - **Affinity** (`TrackSignals.affinity`): in-Stash plays in the last 180
 *    days, log-normalized and exponentially decayed (30-day half-life),
 *    plus a Last.fm cross-source playcount supplement. Recipe's
 *    [StashMixRecipeEntity.affinityBias] shifts the weight:
 *      - positive bias (+) → heavy rotation favorites bubble up
 *      - negative bias (−) → Rediscovery surfaces tracks you *have* liked
 *        but haven't played recently
 *
 *  - **Tag cosine** (`TrackSignals.tagCosine`): cosine similarity between the
 *    track's tag-weight vector and the user's L2-normalized tag-affinity
 *    vector (computed from decayed listening history). Replaces the
 *    older "sum of include-tag weights" heuristic — every recipe now
 *    gets a tag-affinity signal, not just those with explicit tags.
 *
 *  - **Completion** (`TrackSignals.completion`): completed-listen ratio over
 *    the last 60 days. Tracks the user habitually finishes get a small
 *    boost; unknown tracks default to 0.5 (neutral).
 *
 *  - **Loved boost**: additive bonus for Last.fm-loved tracks.
 *
 *  - **Skip penalty** (`TrackSignals.skipPenalty`): subtracted from the
 *    score when a track's 14-day skip-rate is high (linear ramp from
 *    0.4, full penalty at 0.6+).
 *
 * Freshness is a hard pre-filter (Step 5), not a scoring term — a small
 * jitter is added at sort time so the same score doesn't produce
 * identical ordering day-to-day.
 *
 * ## Discovery slots
 *
 * The recipe's `discovery_ratio` reserves that fraction of the target
 * length for tracks not yet in the library. This generator does NOT fetch
 * those — it only queues candidate entries into [DiscoveryQueueEntity]
 * for the [com.stash.core.data.sync.workers.StashDiscoveryWorker] to
 * resolve asynchronously. If no discovery tracks are ready yet, the
 * library-only portion stretches to fill the mix (users never see an
 * empty-looking mix because discovery didn't finish).
 */
@Singleton
class MixGenerator @Inject constructor(
    private val trackDao: TrackDao,
    private val trackTagDao: TrackTagDao,
    private val listeningEventDao: ListeningEventDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val trackSkipEventDao: com.stash.core.data.db.dao.TrackSkipEventDao,
) {

    /** Shared taste signals — same maths the autoplay engine ranks with. */
    private val signals = TrackSignals(listeningEventDao, trackTagDao, trackSkipEventDao)

    companion object {
        private const val BASE_AFFINITY_WEIGHT = 0.40f      // was 0.50; tag-cosine takes some
        private const val BASE_TAG_WEIGHT      = 0.35f      // was 0.30
        private const val BASE_COMPLETION_W    = 0.10f      // NEW
        private const val LOVED_BOOST          = 0.5f       // additive, not weight
        private const val SORT_JITTER          = 0.10f      // was 0.05; ~12% of nominal score range
    }

    /**
     * Produces the finalized track list for [recipe]. The worker calling
     * this replaces the playlist_tracks rows for [recipe.playlistId] with
     * this ordering.
     *
     * v0.9.20: [excludeIds] is the cross-mix dedup primitive — when the
     * refresh worker iterates multiple recipes back-to-back, it accumulates
     * track ids already claimed by earlier recipes and passes them here so
     * the current recipe can't re-pick them.
     */
    suspend fun generate(
        recipe: StashMixRecipeEntity,
        excludeIds: Set<Long> = emptySet(),
    ): List<TrackEntity> {
        // Step 1: candidate pool — start from every downloaded,
        // non-blacklisted track in the library. v0.9.20: filter through
        // excludeIds first (cheap O(n) set lookup, before any other filtering).
        val rawPool = trackDao.getAllDownloaded()
        val pool0 = if (excludeIds.isEmpty()) rawPool else rawPool.filter { it.id !in excludeIds }

        // Step 2: era filter (cheap, done in-memory).
        var pool = filterByEra(pool0, recipe)

        // Step 3: include-tag filter (one DAO hit if recipe has tags).
        val includeTags = recipe.includeTagsCsv.splitTrim()
        if (includeTags.isNotEmpty()) {
            val taggedIds = trackTagDao.getTrackIdsByTags(includeTags).toSet()
            pool = pool.filter { it.id in taggedIds }
        }

        // Step 4: exclude-tag hard filter.
        val excludeTags = recipe.excludeTagsCsv.splitTrim()
        if (excludeTags.isNotEmpty()) {
            val excludedIds = trackTagDao.getTrackIdsMatchingAny(excludeTags).toSet()
            pool = pool.filter { it.id !in excludedIds }
        }

        // Step 5: freshness filter — exclude tracks played inside the window.
        if (recipe.freshnessWindowDays > 0) {
            val since = System.currentTimeMillis() -
                recipe.freshnessWindowDays * 24L * 60 * 60 * 1000
            val recentlyPlayedIds = listeningEventDao
                .getTrackIdsPlayedSince(since)
                .toSet()
            pool = pool.filter { it.id !in recentlyPlayedIds }
        }

        if (pool.isEmpty()) return emptyList()

        // Step 6: score + sort.
        val userVector = signals.userTagVector()
        val affinityMap = signals.affinity(pool)
        val tagCosineMap = signals.tagCosine(pool, userVector)
        val completionMap = signals.completion(pool)
        val skipPenaltyMap = signals.skipPenalty(pool)

        val wAff = BASE_AFFINITY_WEIGHT + recipe.affinityBias * 0.3f
        val wTag = BASE_TAG_WEIGHT
        val wCmp = BASE_COMPLETION_W

        // Seed the score jitter deterministically per recipe + day. An
        // unseeded Random re-shuffled the library slice on every refresh, so
        // any recipe with a fractional library slot (every MULTI-genre custom
        // mix, whose discoveryRatio rounds below 1.0) produced a different
        // finalOrderedIds each pass. That defeated StashMixRefreshWorker
        // .materializeMix's idempotency short-circuit and let the refresh loop
        // visibly clear+reinsert the mix ("load 40, then repopulate"). Seeding
        // by recipe+day keeps a day's refreshes identical (short-circuit fires)
        // while still rotating the order daily.
        val jitterRng = Random(recipe.id * 1_000_003L xor (System.currentTimeMillis() / 86_400_000L))
        val scored = pool.map { track ->
            val aff = affinityMap[track.id] ?: 0f
            val tag = tagCosineMap[track.id] ?: 0f
            val cmp = completionMap[track.id] ?: 0.5f         // unknown -> neutral
            val loved = if (track.lastfmUserLoved) LOVED_BOOST else 0f
            val skip = skipPenaltyMap[track.id] ?: 0f
            val score = aff * wAff +
                tag * wTag +
                cmp * wCmp +
                loved -
                skip +
                jitterRng.nextFloat() * SORT_JITTER
            track to score
        }

        // Step 7: library slots = targetLength * (1 - discoveryRatio), but
        // if we ran out of library candidates we fill up to targetLength.
        // Avoid back-to-back same-artist within the first stretch.
        val librarySlots = (recipe.targetLength * (1f - recipe.discoveryRatio)).toInt()
            .coerceAtLeast(0)
        val ordered = scored.sortedByDescending { it.second }.map { it.first }

        val picked = pickWithArtistSpread(
            candidates = ordered,
            desired = librarySlots.coerceAtMost(pool.size),
        )

        // Step 8: shortfall backfill from the remaining library pool.
        // v0.9.20: gate raised from `< 1.0f` to `< 0.5f`. Recipes that should
        // be substantially discovery-driven (>= 50% by ratio) must not silently
        // degrade to library when discovery is sparse — that's exactly what
        // produced the "library-heavy mixes" symptom on existing installs.
        // A sparser-but-honest mix is better than a deceptively library-filled
        // one. Library-only recipes (ratio == 0) and lightly-discovery recipes
        // (< 0.5) still get the original full-fill behavior.
        if (recipe.discoveryRatio < 0.5f) {
            val shortfall = recipe.targetLength - picked.size
            if (shortfall > 0 && picked.size < pool.size) {
                val extra = ordered.filter { it !in picked }.take(shortfall)
                return picked + extra
            }
        }

        return picked
    }

    /**
     * v0.9.16: Top-N user tags ordered by tag-affinity weight. Used by
     * [com.stash.core.data.sync.workers.StashMixRefreshWorker] to drive
     * the TAG_GRAPH seed strategy.
     *
     * v0.9.19: when the listening-affinity vector is empty (fresh install,
     * recently played tracks not yet enriched, etc.) falls back to the
     * library-wide tag histogram. The histogram represents "what kind of
     * music this user collects" — the right anchor for First Listen's
     * "wider net" semantics when there's no per-play signal yet. Returns
     * an empty list ONLY when the user has zero tags anywhere in
     * `track_tags` (truly fresh install, enrichment hasn't run a single
     * batch yet) — at which point TAG_GRAPH-driven recipes correctly
     * stay empty until the user's library has any tag data.
     */
    suspend fun computeUserTopTags(limit: Int = 10): List<String> {
        val vector = signals.userTagVector()
        if (vector.isNotEmpty()) {
            return vector.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }
        return trackTagDao.getTagHistogram()
            .asSequence()
            .filter { it.tag != "__untaggable__" }
            .take(limit)
            .map { it.tag }
            .toList()
    }

    /**
     * Greedy artist-spread pick. Walks [candidates] in score order and
     * takes each track unless its artist was the previous pick — in which
     * case we look ahead to the next slot and insert the current track
     * later. Prevents a mix starting with 4 straight same-artist songs
     * even when they have the highest scores.
     */
    private fun pickWithArtistSpread(
        candidates: List<TrackEntity>,
        desired: Int,
    ): List<TrackEntity> {
        if (desired <= 0 || candidates.isEmpty()) return emptyList()
        val result = ArrayList<TrackEntity>(desired)
        val remaining = ArrayDeque(candidates)
        while (result.size < desired && remaining.isNotEmpty()) {
            val next = remaining.removeFirst()
            val prevArtist = result.lastOrNull()?.artist?.lowercase()
            if (prevArtist != null && next.artist.lowercase() == prevArtist) {
                // Find a different-artist track in the lookahead.
                val swapIdx = remaining.indexOfFirst { it.artist.lowercase() != prevArtist }
                if (swapIdx >= 0) {
                    val swap = remaining.removeAt(swapIdx)
                    result += swap
                    remaining.addFirst(next) // put original back at the head
                    continue
                }
            }
            result += next
        }
        return result
    }

    private fun filterByEra(
        pool: List<TrackEntity>,
        recipe: StashMixRecipeEntity,
    ): List<TrackEntity> {
        if (recipe.eraStartYear == null && recipe.eraEndYear == null) return pool
        // TrackEntity has no direct `year`. Best available proxy is the
        // `date_added` Instant for now; a real year column would require
        // another migration and tag-scanner work. For v0.4.0 we treat
        // era_{start,end} as date_added year, which is approximate but
        // matches "Throwback" (library tracks older than N years) well
        // enough. Tag-based recipes (90s Alternative) should rely on tags
        // rather than era for accurate results.
        val startYear = recipe.eraStartYear
        val endYear = recipe.eraEndYear
        return pool.filter { track ->
            val year = track.dateAdded.atZone(java.time.ZoneId.systemDefault()).year
            (startYear == null || year >= startYear) &&
                (endYear == null || year <= endYear)
        }
    }

    /**
     * After a refresh, queue discovery candidates. Pulls the user's top
     * artists from [ListeningEventDao.getTopArtistsSince], fetches similar
     * artists per seed, takes the top tracks from each, and files
     * `discovery_queue` rows that the [StashDiscoveryWorker] will resolve.
     *
     * The worker itself performs no Last.fm calls — it's pure Kotlin. That
     * keeps the expensive network work off the refresh critical path.
     * Called from [com.stash.core.data.sync.workers.StashMixRefreshWorker]
     * after each successful recipe refresh with the list of seed artists.
     */
    suspend fun queueDiscoveryCandidates(
        recipe: StashMixRecipeEntity,
        similarArtistSuggestions: List<DiscoveryCandidate>,
    ) {
        if (similarArtistSuggestions.isEmpty()) return
        val toInsert = similarArtistSuggestions.mapNotNull { cand ->
            // v0.9.15: Skip blocklisted identities so the same blocked
            // artist+title doesn't get re-queued every refresh and end up
            // re-discovered via StashDiscoveryWorker.
            if (blocklistGuard.isBlocked(
                    artist = cand.artist, title = cand.title,
                    spotifyUri = null, youtubeId = null,
                )) {
                return@mapNotNull null
            }
            // v0.9.16: 30-day TTL on dedup so candidates that failed download
            // or were skipped/blocked previously can re-enter the funnel after
            // a month — keeps the discovery surface fresh.
            val dedupSinceMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val exists = discoveryQueueDao.existsForRecipeSince(
                recipe.id, cand.artist, cand.title, dedupSinceMs,
            )
            if (exists) null else DiscoveryQueueEntity(
                recipeId = recipe.id,
                artist = cand.artist,
                title = cand.title,
                seedArtist = cand.seedArtist,
            )
        }
        if (toInsert.isNotEmpty()) discoveryQueueDao.insertAllIfNew(toInsert)
    }

    /**
     * Candidate shape for [queueDiscoveryCandidates]. Produced by the
     * refresh worker from Last.fm similar-artist + top-track queries.
     */
    data class DiscoveryCandidate(
        val artist: String,
        val title: String,
        val seedArtist: String,
    )

    private fun String.splitTrim(): List<String> =
        this.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
}
