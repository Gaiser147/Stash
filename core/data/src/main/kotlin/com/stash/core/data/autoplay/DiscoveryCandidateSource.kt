package com.stash.core.data.autoplay

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.lastfm.LastFmApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Candidate generation for songs NOT in the library yet (stage 1 for
 * [AutoplayOrigin.DISCOVERY]) — the "new music" half of autoplay.
 *
 *  - **Similar tracks**: Last.fm `track.getSimilar` for the strongest session
 *    seeds (the highest-precision "sounds like this" signal).
 *  - **Neighbour artists**: top tracks of the session's closest Last.fm
 *    neighbour artists, for breadth when track-level similarity is sparse.
 *
 * Filtered against everything the user already has or has rejected: library
 * identities, songs this session already queued, session-blocked artists,
 * the blocklist, and songs the user keeps early-skipping. Last.fm responses
 * are cached by [LastFmApiClient], so repeated batches in one session cost
 * almost nothing.
 */
@Singleton
class DiscoveryCandidateSource @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val blocklistGuard: BlocklistGuard,
    private val trackSkipEventDao: TrackSkipEventDao,
) {

    /**
     * @param excludedKeys library identities + identities already queued.
     * @param blockedArtists lowercased artists rejected this session.
     * @param userArtistAffinity lowercased artist → long-term play share in [0, 1].
     */
    suspend fun candidates(
        context: AutoplayContext,
        excludedKeys: Set<String>,
        blockedArtists: Set<String>,
        userArtistAffinity: Map<String, Float>,
        keyOf: (artist: String, title: String) -> String,
    ): List<AutoplayCandidate> {
        if (context.seeds.isEmpty()) return emptyList()
        val raw = LinkedHashMap<String, Raw>()
        fun offer(artist: String, title: String, match: Float) {
            if (artist.isBlank() || title.isBlank()) return
            val key = keyOf(artist, title)
            val existing = raw[key]
            if (existing == null || existing.match < match) raw[key] = Raw(artist, title, match)
        }

        for (seed in context.seeds.take(SIMILAR_TRACK_SEEDS)) {
            lastFm.getSimilarTracks(seed.artist, seed.title, limit = SIMILAR_TRACK_LIMIT)
                .getOrDefault(emptyList())
                .forEach { offer(it.artist, it.title, it.match.coerceIn(0f, 1f) * seed.weight) }
        }

        val seedArtists = context.seeds.map { it.artist.trim().lowercase() }.toSet()
        val neighbours = context.artistWeights.entries
            .filter { it.key !in seedArtists && it.key !in blockedArtists }
            .sortedByDescending { it.value }
            .take(NEIGHBOUR_ARTISTS)
        for ((artist, weight) in neighbours) {
            lastFm.getArtistTopTracks(artist, limit = NEIGHBOUR_TOP_TRACKS)
                .getOrDefault(emptyList())
                .forEach { offer(it.artist.ifBlank { artist }, it.title, weight * NEIGHBOUR_DISCOUNT) }
        }
        if (raw.isEmpty()) return emptyList()

        val banned = trackSkipEventDao.getEarlySkipBannedCanonicalKeys(
            minSkips = BANNED_MIN_SKIPS,
            sinceMs = System.currentTimeMillis() - BANNED_WINDOW_MS,
            maxPositionMs = BANNED_MAX_POSITION_MS,
        ).toHashSet()

        val out = ArrayList<AutoplayCandidate>()
        for ((key, r) in raw) {
            val artistKey = r.artist.trim().lowercase()
            if (key in excludedKeys || key in banned || artistKey in blockedArtists) continue
            if (blocklistGuard.isBlocked(r.artist, r.title, spotifyUri = null, youtubeId = null)) continue
            out += AutoplayCandidate(
                key = key,
                artist = r.artist,
                title = r.title,
                origin = AutoplayOrigin.DISCOVERY,
                sessionSim = maxOf(context.artistWeight(r.artist), r.match),
                affinity = userArtistAffinity[artistKey] ?: 0f,
                lastFmMatch = r.match,
            )
            if (out.size >= MAX_CANDIDATES) break
        }
        return out
    }

    private data class Raw(val artist: String, val title: String, val match: Float)

    companion object {
        const val SIMILAR_TRACK_SEEDS = 3
        const val SIMILAR_TRACK_LIMIT = 40
        const val NEIGHBOUR_ARTISTS = 4
        const val NEIGHBOUR_TOP_TRACKS = 5

        /** Neighbour-artist top tracks are less targeted than track-level similars. */
        const val NEIGHBOUR_DISCOUNT = 0.6f
        const val MAX_CANDIDATES = 150

        // Same shape as the mix pipeline's early-skip ban, one skip stricter
        // (autoplay is interactive: a song skipped twice shouldn't come back).
        const val BANNED_MIN_SKIPS = 2
        const val BANNED_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
        const val BANNED_MAX_POSITION_MS = 30_000L
    }
}
