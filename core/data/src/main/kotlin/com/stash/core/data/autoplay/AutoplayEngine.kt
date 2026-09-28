package com.stash.core.data.autoplay

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.mix.TrackSignals
import com.stash.core.data.prefs.AutoplayPreference
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.YTMusicApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Spotify-style autoplay: when the queue the user started runs out, keep
 * playing songs that fit — mostly from their own library, mixed with new
 * songs they don't have yet.
 *
 * ## Pipeline (per batch)
 *  1. **Context** ([buildContext]) — a recency-weighted window over what was
 *     just heard. Completed songs pull the session towards them, early skips
 *     push it away (their tags are subtracted, a skipped discovery blocks its
 *     artist for the session). So the continuation follows the listener
 *     instead of staying pinned to the first song.
 *  2. **Candidates** — [LibraryCandidateSource] (transitions, shared
 *     playlists, artist neighbourhood, tags) and, online only,
 *     [DiscoveryCandidateSource] (Last.fm similar tracks / neighbour artists,
 *     minus everything already owned or rejected).
 *  3. **Rank + assemble** — [AutoplayRanker]: weighted score, softmax
 *     sampling, artist spread, and a discovery share drawn from the learned
 *     [DiscoveryAcceptance] bandit.
 *  4. **Materialize** — library picks play as-is; discoveries are resolved
 *     to a playable YouTube id (lossless sources are still tried first by the
 *     normal stream chain at play time) exactly like radio tracks.
 *
 * Feedback arrives through [recordOutcome] for every song heard while the
 * session is armed and feeds both the session context and the persisted
 * discovery belief.
 *
 * Offline / streaming off → discovery share is 0 and only downloaded songs
 * are considered, so autoplay still works on a plane.
 */
@Singleton
class AutoplayEngine @Inject constructor(
    private val trackDao: TrackDao,
    private val listeningEventDao: ListeningEventDao,
    private val signals: TrackSignals,
    private val library: LibraryCandidateSource,
    private val discovery: DiscoveryCandidateSource,
    private val lastFm: LastFmApiClient,
    private val yt: YTMusicApiClient,
    private val matcher: TrackMatcher,
    private val preference: AutoplayPreference,
) {

    suspend fun isEnabled(): Boolean = runCatching { preference.isEnabled() }.getOrDefault(false)

    /** Starts a session for the queue the user just started. */
    suspend fun start(queue: List<Track>, random: Random = Random(System.nanoTime())): AutoplaySession {
        val longTerm = runCatching { preference.acceptance() }.getOrDefault(DiscoveryAcceptance())
        val seeds = queue.map {
            AutoplaySession.SeedSong(it.id, it.artist, it.title, AutoplaySession.keyOf(matcher, it.artist, it.title))
        }
        return AutoplaySession(seeds, random, longTerm)
    }

    /**
     * The next [size] songs to append.
     *
     * @param includeStreamable library stream-only rows are playable now.
     * @param allowDiscovery the network allows streaming songs not in the library.
     */
    suspend fun nextBatch(
        session: AutoplaySession,
        includeStreamable: Boolean,
        allowDiscovery: Boolean,
        size: Int = BATCH_SIZE,
    ): List<Track> {
        val all = trackDao.getAllPlayable(includeStreamable)
        val libraryIds = all.mapTo(HashSet(all.size)) { it.id }
        val libraryKeys = HashSet<String>(all.size)
        val pool = ArrayList<TrackEntity>(all.size)
        for (t in all) {
            val key = keyOf(t)
            libraryKeys += key
            if (key in session.emittedKeys) continue
            if (t.artist.trim().lowercase() in session.blockedArtists) continue
            pool += t
        }

        val context = buildContext(session, libraryIds, online = allowDiscovery)
        val libCands = library.candidates(context, pool, ::keyOf, session.random)
        val discCands = if (allowDiscovery) {
            runCatching {
                discovery.candidates(
                    context = context,
                    excludedKeys = libraryKeys + session.emittedKeys,
                    blockedArtists = session.blockedArtists,
                    userArtistAffinity = userArtistAffinity(),
                    keyOf = { a, t -> AutoplaySession.keyOf(matcher, a, t) },
                )
            }.onFailure { Log.w(TAG, "discovery candidates failed; library only", it) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val share = if (discCands.isEmpty()) 0f else session.acceptance.sampleShare(session.random)
        // Over-provision: a discovery that fails to resolve is dropped, not retried.
        val picks = AutoplayRanker.assemble(
            library = libCands,
            discovery = discCands,
            size = size + OVER_PROVISION,
            discoveryShare = share,
            recentArtists = context.recentArtists,
            random = session.random,
            openFamiliar = session.batches == 0,
        )

        val out = ArrayList<Track>(size)
        for (pick in picks) {
            if (out.size >= size) break
            val track = materialize(pick) ?: continue
            if (out.any { it.id == track.id }) continue
            session.emittedKeys += pick.key
            session.picksById[track.id] = pick
            out += track
        }
        session.batches++
        Log.d(TAG, "batch ${session.batches}: ${out.size} songs, share=$share, lib=${libCands.size}, disc=${discCands.size}")
        return out
    }

    /**
     * Feedback for a song heard while the session was armed — an original
     * queue song or an autoplay pick.
     *
     * @param listenedMs how far into the song playback got.
     * @param durationMs the song's length, 0 when unknown.
     */
    suspend fun recordOutcome(session: AutoplaySession, track: Track, listenedMs: Long, durationMs: Long) {
        val pick = session.picksById[track.id]
        val outcome = classify(listenedMs, durationMs)
        val artistKey = track.artist.trim().lowercase()
        session.history += HeardSong(
            trackId = track.id,
            artist = track.artist,
            title = track.title,
            key = pick?.key ?: AutoplaySession.keyOf(matcher, track.artist, track.title),
            origin = pick?.origin,
            outcome = outcome,
        )
        val isDiscovery = pick?.origin == AutoplayOrigin.DISCOVERY
        when (outcome) {
            ListenOutcome.EARLY_SKIP -> {
                val skips = (session.artistSkips[artistKey] ?: 0) + 1
                session.artistSkips[artistKey] = skips
                if (isDiscovery || skips >= LIBRARY_SKIPS_TO_BLOCK) session.blockedArtists += artistKey
                if (isDiscovery) {
                    session.sessionAcceptance = session.sessionAcceptance.recordFailure()
                    runCatching { preference.updateAcceptance { it.recordFailure() } }
                }
            }
            ListenOutcome.COMPLETED -> if (isDiscovery) {
                session.sessionAcceptance = session.sessionAcceptance.recordSuccess()
                runCatching { preference.updateAcceptance { it.recordSuccess() } }
            }
            ListenOutcome.PLAYED -> Unit
        }
    }

    /**
     * Builds the [AutoplayContext] from the newest heard songs (padded with
     * the end of the starting queue while little has been heard).
     */
    internal suspend fun buildContext(
        session: AutoplaySession,
        libraryIds: Set<Long>,
        online: Boolean,
    ): AutoplayContext {
        fun libId(id: Long?) = id?.takeIf { it in libraryIds }

        // Positive window: heard songs that weren't rejected, newest first.
        data class Raw(val libraryId: Long?, val artist: String, val title: String, val base: Float)
        val raw = ArrayList<Raw>()
        for (h in session.history.asReversed()) {
            if (raw.size >= SEED_WINDOW) break
            if (h.outcome == ListenOutcome.EARLY_SKIP) continue
            val base = if (h.outcome == ListenOutcome.COMPLETED) 1.2f else 1f
            raw += Raw(libId(h.trackId), h.artist, h.title, base)
        }
        val heardKeys = session.history.mapTo(HashSet()) { it.key }
        for (s in session.startQueue.asReversed()) {
            if (raw.size >= SEED_WINDOW) break
            if (s.key in heardKeys) continue
            raw += Raw(libId(s.trackId), s.artist, s.title, QUEUE_ONLY_WEIGHT)
        }
        val seeds = raw.mapIndexed { i, r ->
            AutoplayContext.Seed(r.libraryId, r.artist, r.title, (r.base * RECENCY.pow(i)).coerceAtMost(1f))
        }

        // Session tag vector: seeds minus half of recent early skips.
        val tagAcc = HashMap<String, Float>()
        for (s in seeds) {
            val id = s.libraryId ?: continue
            signals.tagsOf(id).forEach { (tag, w) -> tagAcc[tag] = (tagAcc[tag] ?: 0f) + w * s.weight }
        }
        val negatives = session.history.asReversed()
            .take(NEGATIVE_WINDOW)
            .filter { it.outcome == ListenOutcome.EARLY_SKIP }
        for (n in negatives) {
            val id = libId(n.trackId) ?: continue
            signals.tagsOf(id).forEach { (tag, w) -> tagAcc[tag] = (tagAcc[tag] ?: 0f) - NEGATIVE_TAG_WEIGHT * w }
        }
        val tagVector = l2Normalize(tagAcc.filterValues { it > 0f })

        // Artist neighbourhood: seed artists, plus Last.fm neighbours online.
        val artistAcc = HashMap<String, Float>()
        fun bump(artist: String, w: Float) {
            val k = artist.trim().lowercase()
            if (k.isBlank() || k in session.blockedArtists) return
            artistAcc[k] = maxOf(artistAcc[k] ?: 0f, w)
        }
        seeds.forEach { bump(it.artist, it.weight) }
        if (online) {
            val topArtists = seeds.groupBy { it.artist.trim().lowercase() }
                .map { (_, v) -> v.first().artist to v.maxOf { it.weight } }
                .sortedByDescending { it.second }
                .take(NEIGHBOUR_SEED_ARTISTS)
            for ((artist, w) in topArtists) {
                lastFm.getSimilarArtists(artist, limit = NEIGHBOURS_PER_ARTIST)
                    .getOrDefault(emptyList())
                    .forEach { bump(it.name, w * it.match.coerceIn(0f, 1f) * NEIGHBOUR_DISCOUNT) }
            }
        }

        val recentArtists = session.history.takeLast(AutoplayRanker.ARTIST_GAP)
            .map { it.artist.trim().lowercase() }
            .ifEmpty { session.startQueue.takeLast(AutoplayRanker.ARTIST_GAP).map { it.artist.trim().lowercase() } }

        return AutoplayContext(seeds, tagVector, artistAcc, recentArtists)
    }

    private suspend fun userArtistAffinity(): Map<String, Float> {
        val rows = listeningEventDao.getTopArtistsSince(
            System.currentTimeMillis() - TrackSignals.AFFINITY_WINDOW_MS,
            limit = 100,
        )
        val max = rows.maxOfOrNull { it.plays }?.coerceAtLeast(1) ?: return emptyMap()
        return rows.associate { it.artist.trim().lowercase() to it.plays.toFloat() / max }
    }

    private suspend fun materialize(pick: AutoplayCandidate): Track? {
        pick.track?.let { return it.toDomain() }
        val match = runCatching { yt.searchCanonicalMatch(pick.artist, pick.title) }.getOrNull() ?: return null
        return Track(
            // Same synthetic-id scheme as radio tracks, so every downstream
            // consumer (like, queue ops, resume) treats both identically.
            id = match.videoId.hashCode().toLong(),
            title = pick.title,
            artist = pick.artist,
            youtubeId = match.videoId,
            albumArtUrl = match.thumbnailUrl ?: "https://i.ytimg.com/vi/${match.videoId}/mqdefault.jpg",
            source = MusicSource.YOUTUBE,
            isStreamable = true,
        )
    }

    // Always recomputed from the raw fields (never the stored canonical
    // columns) so library rows, queue seeds and discoveries share one scheme.
    private fun keyOf(t: TrackEntity): String = AutoplaySession.keyOf(matcher, t.artist, t.title)

    companion object {
        private const val TAG = "AutoplayEngine"

        /** Songs appended per grow. Small, so the next batch re-reads fresh feedback. */
        const val BATCH_SIZE = 4
        private const val OVER_PROVISION = 3

        const val SEED_WINDOW = 5
        private const val RECENCY = 0.7f
        private const val QUEUE_ONLY_WEIGHT = 0.8f
        private const val NEGATIVE_WINDOW = 10
        private const val NEGATIVE_TAG_WEIGHT = 0.5f

        private const val NEIGHBOUR_SEED_ARTISTS = 3
        private const val NEIGHBOURS_PER_ARTIST = 30
        private const val NEIGHBOUR_DISCOUNT = 0.8f

        /** Early-skipping two songs by one library artist blocks them for the session. */
        const val LIBRARY_SKIPS_TO_BLOCK = 2

        const val EARLY_SKIP_MS = 30_000L
        private const val UNKNOWN_DURATION_COMPLETE_MS = 150_000L

        internal fun classify(listenedMs: Long, durationMs: Long): ListenOutcome = when {
            listenedMs < EARLY_SKIP_MS && (durationMs <= 0 || listenedMs < durationMs / 2) ->
                ListenOutcome.EARLY_SKIP
            durationMs > 0 && listenedMs >= durationMs * 8 / 10 -> ListenOutcome.COMPLETED
            durationMs <= 0 && listenedMs >= UNKNOWN_DURATION_COMPLETE_MS -> ListenOutcome.COMPLETED
            else -> ListenOutcome.PLAYED
        }

        private fun l2Normalize(v: Map<String, Float>): Map<String, Float> {
            val mag = sqrt(v.values.sumOf { (it * it).toDouble() }).toFloat()
            return if (mag <= 0f) emptyMap() else v.mapValues { it.value / mag }
        }
    }
}
