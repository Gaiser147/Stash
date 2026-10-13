package com.stash.core.data.autoplay

import android.util.Log
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryFeedbackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Candidate generation for songs NOT in the library yet (stage 1 for
 * [AutoplayOrigin.DISCOVERY]) — the "new music" half of autoplay.
 *
 * ## Sources
 *  - **YouTube Music radio** for up to [RADIO_SEEDS] seeds that have a video
 *    id: YouTube's own "songs like this", the strongest similarity signal
 *    available and independent of Last.fm's sparse long tail.
 *  - **Similar tracks**: Last.fm `track.getSimilar` for the strongest seeds.
 *  - **Neighbour artists**: top tracks of the session's closest Last.fm
 *    neighbour artists, for breadth when track-level similarity is sparse.
 *
 * ## Quality
 *  - Everything the user already has or rejected is dropped: library
 *    identities, songs already queued, session-blocked artists, the
 *    blocklist, songs early-skipped twice, and artists whose discoveries the
 *    user keeps skipping across sessions ([DiscoveryFeedbackDao]).
 *  - Obscure Last.fm results (fewer than [MIN_PLAYCOUNT] scrobbles) are
 *    dropped unless that would leave too few.
 *  - Live/remix/cover/nightcore/sped-up versions only pass when the library
 *    itself holds such versions ([VariantKind]).
 *  - No artist contributes more than [MAX_PER_ARTIST] candidates, so one
 *    neighbour can't fill the whole discovery side.
 *  - The best [TAGGED_CANDIDATES] get their Last.fm tags (cached) and a
 *    mood check against the session ([MoodSpace]); a clashing mood is
 *    dropped, the rest carry `tagFit` into the ranking.
 */
@Singleton
class DiscoveryCandidateSource @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val yt: YTMusicApiClient,
    private val blocklistGuard: BlocklistGuard,
    private val trackSkipEventDao: TrackSkipEventDao,
    private val feedbackDao: DiscoveryFeedbackDao,
) {

    /**
     * @param excludedKeys library identities + identities already queued.
     * @param blockedArtists lowercased artists rejected this session.
     * @param userArtistAffinity lowercased artist → long-term play share in [0, 1].
     * @param seedVideoIds video ids of the strongest seeds, newest first.
     * @param variantShare share of each [VariantKind] in the library.
     */
    suspend fun candidates(
        context: AutoplayContext,
        excludedKeys: Set<String>,
        blockedArtists: Set<String>,
        userArtistAffinity: Map<String, Float>,
        keyOf: (artist: String, title: String) -> String,
        seedVideoIds: List<String> = emptyList(),
        variantShare: Map<VariantKind, Float> = emptyMap(),
    ): List<AutoplayCandidate> = coroutineScope {
        if (context.seeds.isEmpty()) return@coroutineScope emptyList()
        val raw = LinkedHashMap<String, Raw>()
        fun offer(artist: String, title: String, match: Float, playcount: Long?, source: String) {
            if (artist.isBlank() || title.isBlank()) return
            val key = keyOf(artist, title)
            val existing = raw[key]
            if (existing == null || existing.match < match) {
                raw[key] = Raw(artist, title, match, playcount ?: existing?.playcount, source)
            }
        }

        val radio = seedVideoIds.distinct().take(RADIO_SEEDS).mapIndexed { i, id ->
            async { Triple(i, id, runCatching { yt.getRadio(id, RADIO_LIMIT) }.getOrDefault(emptyList())) }
        }
        val similar = context.seeds.take(SIMILAR_TRACK_SEEDS).map { seed ->
            async { seed to lastFm.getSimilarTracks(seed.artist, seed.title, limit = SIMILAR_TRACK_LIMIT).getOrDefault(emptyList()) }
        }
        val seedArtists = context.seeds.map { it.artist.trim().lowercase() }.toSet()
        val neighbours = context.artistWeights.entries
            .filter { it.key !in seedArtists && it.key !in blockedArtists }
            .sortedByDescending { it.value }
            .take(NEIGHBOUR_ARTISTS)
            .map { (artist, weight) ->
                async { Triple(artist, weight, lastFm.getArtistTopTracks(artist, limit = NEIGHBOUR_TOP_TRACKS).getOrDefault(emptyList())) }
            }

        for ((seedIndex, _, tracks) in radio.awaitAll()) {
            // Radio order is YouTube's own ranking: earlier = closer.
            val seedWeight = context.seeds.getOrNull(seedIndex)?.weight ?: 0.8f
            tracks.forEachIndexed { pos, t ->
                // Anonymous radio marks every row UGC, so only podcasts are
                // dropped here; the song is matched to its master at play time.
                if (t.musicVideoType == MusicVideoType.PODCAST_EPISODE) return@forEachIndexed
                val match = (RADIO_MATCH_TOP - pos * RADIO_MATCH_STEP).coerceAtLeast(RADIO_MATCH_MIN) * seedWeight
                offer(t.artist, t.title, match, playcount = null, source = "radio")
            }
        }
        for ((seed, tracks) in similar.awaitAll()) {
            tracks.forEach { offer(it.artist, it.title, it.match.coerceIn(0f, 1f) * seed.weight, it.playcount, "similar") }
        }
        for ((artist, weight, tracks) in neighbours.awaitAll()) {
            tracks.forEach {
                offer(it.artist.ifBlank { artist }, it.title, weight * NEIGHBOUR_DISCOUNT, it.playcount.toLong(), "neighbour")
            }
        }
        if (raw.isEmpty()) return@coroutineScope emptyList()

        val now = System.currentTimeMillis()
        val banned = trackSkipEventDao.getEarlySkipBannedCanonicalKeys(
            minSkips = BANNED_MIN_SKIPS,
            sinceMs = now - BANNED_WINDOW_MS,
            maxPositionMs = BANNED_MAX_POSITION_MS,
        ).toHashSet()
        val rejectedArtists = runCatching {
            feedbackDao.artistStatsSince(now - FEEDBACK_BLOCK_WINDOW_MS)
                .filter { it.earlySkips >= FEEDBACK_BLOCK_SKIPS && it.completed == 0 }
                .mapTo(HashSet()) { it.artistKey }
        }.getOrDefault(emptySet())

        val eligible = raw.entries.filter { (key, r) ->
            val artistKey = r.artist.trim().lowercase()
            key !in excludedKeys && key !in banned && artistKey !in blockedArtists && artistKey !in rejectedArtists &&
                VariantKind.allowed("${r.artist} ${r.title}", variantShare) &&
                !blocklistGuard.isBlocked(r.artist, r.title, spotifyUri = null, youtubeId = null)
        }
        // Popularity floor for Last.fm results; radio has no count and passes.
        val popular = eligible.filter { (_, r) -> r.playcount == null || r.playcount >= MIN_PLAYCOUNT }
        val pool = if (popular.size >= MIN_AFTER_FLOOR) popular else eligible

        val perArtist = HashMap<String, Int>()
        val capped = pool.sortedByDescending { it.value.match }.filter { (_, r) ->
            val k = r.artist.trim().lowercase()
            val n = perArtist[k] ?: 0
            if (n >= MAX_PER_ARTIST) false else { perArtist[k] = n + 1; true }
        }.take(MAX_CANDIDATES)

        // Mood check for the strongest candidates (Last.fm tags are cached).
        val moodByKey = HashMap<String, Map<String, Float>>()
        if (context.moods.isNotEmpty()) {
            capped.take(TAGGED_CANDIDATES).map { (key, r) ->
                async {
                    val tags = lastFm.getTrackTopTags(r.artist, r.title).getOrDefault(emptyList())
                        .ifEmpty { lastFm.getArtistTopTags(r.artist).getOrDefault(emptyList()) }
                    key to MoodSpace.moodsOf(tags.associate { it.name.lowercase() to it.weight })
                }
            }.awaitAll().forEach { (k, m) -> moodByKey[k] = m }
        }

        val out = ArrayList<AutoplayCandidate>()
        var clashes = 0
        for ((key, r) in capped) {
            val moods = moodByKey[key].orEmpty()
            if (MoodSpace.clashes(context.moods, moods)) { clashes++; continue }
            out += AutoplayCandidate(
                key = key,
                artist = r.artist,
                title = r.title,
                origin = AutoplayOrigin.DISCOVERY,
                sessionSim = maxOf(context.artistWeight(r.artist), r.match),
                affinity = userArtistAffinity[r.artist.trim().lowercase()] ?: 0f,
                lastFmMatch = r.match,
                tagFit = MoodSpace.fit(context.moods, moods),
            )
        }
        Log.d(
            TAG,
            "discoveries: raw=${raw.size} (radio=${raw.values.count { it.source == "radio" }}) eligible=${eligible.size} " +
                "popular=${popular.size} out=${out.size} moodClash=$clashes rejectedArtists=${rejectedArtists.size}",
        )
        out
    }

    private data class Raw(val artist: String, val title: String, val match: Float, val playcount: Long?, val source: String)

    companion object {
        private const val TAG = "DiscoverySource"

        const val RADIO_SEEDS = 2
        const val RADIO_LIMIT = 25
        private const val RADIO_MATCH_TOP = 0.95f
        private const val RADIO_MATCH_STEP = 0.02f
        private const val RADIO_MATCH_MIN = 0.45f

        const val SIMILAR_TRACK_SEEDS = 5
        const val SIMILAR_TRACK_LIMIT = 40
        const val NEIGHBOUR_ARTISTS = 4
        const val NEIGHBOUR_TOP_TRACKS = 5

        /** Neighbour-artist top tracks are less targeted than track-level similars. */
        const val NEIGHBOUR_DISCOUNT = 0.6f
        const val MAX_CANDIDATES = 150
        const val MAX_PER_ARTIST = 3
        const val TAGGED_CANDIDATES = 24

        /** Last.fm scrobbles below which a suggestion is too obscure to trust. */
        const val MIN_PLAYCOUNT = 10_000L
        private const val MIN_AFTER_FLOOR = 30

        // Same shape as the mix pipeline's early-skip ban, one skip stricter
        // (autoplay is interactive: a song skipped twice shouldn't come back).
        const val BANNED_MIN_SKIPS = 2
        const val BANNED_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
        const val BANNED_MAX_POSITION_MS = 30_000L

        /** An artist whose discoveries were early-skipped this often, never finished, rests this long. */
        const val FEEDBACK_BLOCK_SKIPS = 2
        const val FEEDBACK_BLOCK_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Song versions that aren't the original recording. A discovery of one of
 * these only passes when the library has a real share of that kind — the
 * user who collects nightcore gets nightcore, everyone else the original.
 */
enum class VariantKind(private val pattern: Regex) {
    NIGHTCORE(Regex("""\bnightcore\b""", RegexOption.IGNORE_CASE)),
    SPED_UP(Regex("""\b(sped|speed)[ -]?up\b|\bslowed\b|\breverb\b""", RegexOption.IGNORE_CASE)),
    LIVE(Regex("""[(\[\-–]\s*live\b|\blive (at|from|in)\b""", RegexOption.IGNORE_CASE)),
    REMIX(Regex("""\bremix\b|\bedit\)|\bbootleg\b""", RegexOption.IGNORE_CASE)),
    COVER(Regex("""\bcover\b|\bkaraoke\b|\binstrumental version\b""", RegexOption.IGNORE_CASE));

    fun matches(title: String) = pattern.containsMatchIn(title)

    companion object {
        /** A kind needs at least this share of the library before discoveries of it pass. */
        const val MIN_LIBRARY_SHARE = 0.03f

        fun of(title: String): VariantKind? = entries.firstOrNull { it.matches(title) }

        fun allowed(title: String, libraryShare: Map<VariantKind, Float>): Boolean {
            val kind = of(title) ?: return true
            return (libraryShare[kind] ?: 0f) >= MIN_LIBRARY_SHARE
        }

        /** Share of each kind among [titles] (artist + title strings). */
        fun shares(titles: Collection<String>): Map<VariantKind, Float> {
            if (titles.isEmpty()) return emptyMap()
            val counts = HashMap<VariantKind, Int>()
            for (t in titles) of(t)?.let { counts[it] = (counts[it] ?: 0) + 1 }
            return counts.mapValues { it.value.toFloat() / titles.size }
        }
    }
}
