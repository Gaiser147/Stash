package com.stash.core.data.autoplay

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mix.TrackSignals
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Candidate generation from the user's own library (stage 1 for
 * [AutoplayOrigin.LIBRARY]).
 *
 * Scoring every library row against the session would cost one tag query per
 * track per batch, so the pool is first narrowed to a shortlist from cheap,
 * high-recall signals, and only the shortlist is featurized:
 *
 *  1. **Transitions** — songs the user habitually plays after the seeds
 *     ([ListeningEventDao.getTransitionsFrom]).
 *  2. **Shared playlists** — songs that sit in the same hand-made playlists
 *     as the seeds ([PlaylistDao.getCoPlaylistTracks]).
 *  3. **Artist neighbourhood** — the seed artists and their Last.fm
 *     neighbours (`AutoplayContext.artistWeights`).
 *  4. **Session tags** — songs carrying the session's strongest tags.
 *  5. **Cold-start filler** — a random slice, so a fresh install with no
 *     history, tags or Last.fm still gets a continuation.
 */
@Singleton
class LibraryCandidateSource @Inject constructor(
    private val signals: TrackSignals,
    private val listeningEventDao: ListeningEventDao,
    private val playlistDao: PlaylistDao,
    private val trackTagDao: TrackTagDao,
) {

    /**
     * @param pool playable library rows already stripped of songs this session
     *   queued and of session-blocked artists.
     */
    suspend fun candidates(
        context: AutoplayContext,
        pool: List<TrackEntity>,
        keyOf: (TrackEntity) -> String,
        random: Random,
    ): List<AutoplayCandidate> {
        if (pool.isEmpty()) return emptyList()
        val byId = pool.associateBy { it.id }
        val seedIds = context.seeds.mapNotNull { it.libraryId }
        val now = System.currentTimeMillis()

        val transitions: Map<Long, Int> = if (seedIds.isEmpty()) emptyMap() else
            listeningEventDao.getTransitionsFrom(
                trackIds = seedIds,
                sinceMs = now - TrackSignals.AFFINITY_WINDOW_MS,
                windowMs = TRANSITION_WINDOW_MS,
            ).associate { it.trackId to it.plays }
        val coPlaylist: Map<Long, Int> = if (seedIds.isEmpty()) emptyMap() else
            playlistDao.getCoPlaylistTracks(seedIds).associate { it.trackId to it.shared }

        // ── Shortlist, highest-precision signals first ──────────────────
        val shortlist = LinkedHashMap<Long, TrackEntity>()
        fun addAll(ids: Iterable<Long>, cap: Int) {
            var added = 0
            for (id in ids) {
                if (shortlist.size >= SHORTLIST_MAX || added >= cap) return
                val t = byId[id] ?: continue
                if (shortlist.putIfAbsent(id, t) == null) added++
            }
        }
        addAll(transitions.keys, cap = 120)
        addAll(coPlaylist.keys, cap = 120)
        if (context.artistWeights.isNotEmpty()) {
            addAll(
                pool.asSequence()
                    .filter { context.artistWeight(it.artist) > 0f }
                    .sortedByDescending { context.artistWeight(it.artist) }
                    .map { it.id }
                    .asIterable(),
                cap = 150,
            )
        }
        val topTags = context.tagVector.entries.sortedByDescending { it.value }.take(TOP_TAGS).map { it.key }
        if (topTags.isNotEmpty()) {
            val tagged = trackTagDao.getTrackIdsMatchingAny(topTags).filter { it in byId }
            addAll(tagged.shuffled(random), cap = 150)
        }
        if (shortlist.size < COLD_START_MIN) {
            addAll(pool.map { it.id }.shuffled(random), cap = COLD_START_MIN - shortlist.size)
        }
        val tracks = shortlist.values.toList()

        // ── Featurize ───────────────────────────────────────────────────
        val tagCos = signals.tagCosine(tracks, context.tagVector)
        val affinity = signals.affinity(tracks)
        val completion = signals.completion(tracks)
        val skip = signals.skipPenalty(tracks)
        val recent = listeningEventDao.getTrackIdsPlayedSince(now - RECENT_WINDOW_MS).toHashSet()

        return tracks.map { t ->
            val artistSim = context.artistWeight(t.artist)
            val sessionSim = if (context.tagVector.isEmpty()) artistSim
                else TAG_SHARE * (tagCos[t.id] ?: 0f) + (1f - TAG_SHARE) * artistSim
            val transition = 0.65f * AutoplayRanker.squash((transitions[t.id] ?: 0).toFloat(), halfAt = 2f) +
                0.35f * AutoplayRanker.squash((coPlaylist[t.id] ?: 0).toFloat(), halfAt = 2f)
            val loved = if (t.lastfmUserLoved || t.stashLikedAt != null) LOVED_BOOST else 0f
            AutoplayCandidate(
                key = keyOf(t),
                artist = t.artist,
                title = t.title,
                origin = AutoplayOrigin.LIBRARY,
                track = t,
                sessionSim = sessionSim,
                affinity = ((affinity[t.id] ?: 0f) + loved).coerceIn(0f, 1f),
                transition = transition,
                completion = completion[t.id] ?: 0.5f,
                skipPenalty = skip[t.id] ?: 0f,
                recentPenalty = if (t.id in recent) RECENT_PENALTY else 0f,
            )
        }
    }

    companion object {
        /** "B follows A" when B starts within this long after A. */
        const val TRANSITION_WINDOW_MS = 15L * 60 * 1000
        const val SHORTLIST_MAX = 400
        const val COLD_START_MIN = 80
        const val TOP_TAGS = 5

        /** Share of sessionSim that comes from tags (rest from artist closeness). */
        const val TAG_SHARE = 0.55f
        const val LOVED_BOOST = 0.2f

        /** Songs played in this window are pushed back to avoid instant repeats. */
        const val RECENT_WINDOW_MS = 12L * 60 * 60 * 1000
        const val RECENT_PENALTY = 0.4f
    }
}
