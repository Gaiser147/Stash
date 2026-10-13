package com.stash.core.data.autoplay

import android.util.Log
import com.stash.core.data.db.dao.AudioFeaturesDao
import com.stash.core.data.db.dao.DiscoveryFeedbackDao
import com.stash.core.data.db.entity.DiscoveryFeedbackEntity
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
    private val audioFeatures: AudioFeaturesDao,
    private val feedbackDao: DiscoveryFeedbackDao,
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
     * "Mix für mich": a ready-to-play queue of [size] songs built from what
     * the user heard most recently, with the same rules as autoplay
     * (familiar opener, artist spread, learned share of new songs). The
     * seeds themselves are not part of the mix. Without any listening
     * history the library's cold-start filler still yields a (more random)
     * mix; an empty list means there is nothing playable at all.
     */
    suspend fun buildMix(
        includeStreamable: Boolean,
        allowDiscovery: Boolean,
        size: Int = MIX_SIZE,
        random: Random = Random(System.nanoTime()),
        shape: ArcShape = ArcShape.RISE,
    ): List<Track> = buildMixFrom(
        session = start(recentSeeds(), random),
        includeStreamable = includeStreamable,
        allowDiscovery = allowDiscovery,
        size = size,
        shape = shape,
    ).second

    /** The songs heard most recently, oldest first (the session treats the end as "just heard"). */
    internal suspend fun recentSeeds(): List<Track> {
        val ids = listeningEventDao.getRecentlyHeardTrackIds(MIX_SEEDS)
        val byId = if (ids.isEmpty()) emptyMap() else trackDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDomain() }.asReversed()
    }

    /**
     * Fills [session] with a [size]-song mix and orders it along [shape].
     * Returns the session too, so the caller can keep autoplay going in the
     * same mood once the mix ends.
     */
    internal suspend fun buildMixFrom(
        session: AutoplaySession,
        includeStreamable: Boolean,
        allowDiscovery: Boolean,
        size: Int,
        shape: ArcShape,
    ): Pair<AutoplaySession, List<Track>> {
        if (session.fixedCorridor == null) {
            // Until something analysed has been heard, aim at what this time
            // of day usually sounds like.
            session.fallbackCorridor = timeOfDayCorridor()
        }
        val mix = nextBatch(session, includeStreamable, allowDiscovery, size)
        val energy = audioProfiles(mix.map { it.id }).mapValues { AudioFlow.energy(it.value) }
        return session to MixArc.arrange(mix, energyOf = { energy[it.id] }, artistOf = { it.artist.trim().lowercase() }, shape = shape)
    }

    /** Typical energy of the current three-hour window, as a corridor; null without enough history. */
    internal suspend fun timeOfDayCorridor(now: Long = System.currentTimeMillis()): MoodCorridor? {
        val rows = runCatching { audioFeatures.heardSoundSince(now - TIME_OF_DAY_WINDOW_MS) }.getOrDefault(emptyList())
        val energy = TimeOfDayEnergy.profile(rows)[TimeOfDayEnergy.bucketOf(now)] ?: return null
        return MoodCorridor(energyCenter = energy, energyWidth = MoodCorridor.DEFAULT_ENERGY_WIDTH * 1.5f)
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

        val baseContext = buildContext(session, libraryIds, online = allowDiscovery)
        // Mood corridor: the session's (or the request's) band of sound,
        // widened until enough of the library fits it.
        val poolProfiles = audioProfiles(pool.map { it.id })
        val corridor = (session.fixedCorridor ?: baseContext.audioTarget?.let(MoodCorridor::around) ?: session.fallbackCorridor)
            ?.let { MoodCorridor.fitted(it, poolProfiles.values) }
        val corridorIds = if (corridor == null) emptyList() else
            poolProfiles.entries.filter { corridor.fit(it.value) >= CORRIDOR_SHORTLIST_FIT }.map { it.key }.shuffled(session.random)
        val context = baseContext.copy(corridor = corridor, corridorTrackIds = corridorIds)

        val libCands = withFlow(context, library.candidates(context, pool, ::keyOf, session.random), poolProfiles)
        val byId = all.associateBy { it.id }
        val seedVideoIds = context.seeds.mapNotNull { s ->
            s.libraryId?.let { byId[it]?.youtubeId }
        } + session.history.asReversed().filter { it.outcome == ListenOutcome.COMPLETED }
            .mapNotNull { h -> h.trackId?.let(session.videoIdsById::get) }
        val discCands = if (allowDiscovery) {
            runCatching {
                discovery.candidates(
                    context = context,
                    excludedKeys = libraryKeys + session.emittedKeys,
                    blockedArtists = session.blockedArtists,
                    userArtistAffinity = userArtistAffinity(),
                    keyOf = { a, t -> AutoplaySession.keyOf(matcher, a, t) },
                    seedVideoIds = seedVideoIds,
                    variantShare = VariantKind.shares(all.map { "${it.artist} ${it.title}" }),
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
            if (pick.origin == AutoplayOrigin.DISCOVERY) track.youtubeId?.let { session.videoIdsById[track.id] = it }
            out += track
        }
        session.batches++
        val chosen = out.mapNotNull { session.picksById[it.id] }
        Log.d(
            TAG,
            "batch ${session.batches}: ${out.size} songs, share=$share, lib=${libCands.size}, disc=${discCands.size}, " +
                "corridor=${corridor ?: "-"} inCorridor=${corridorIds.size}, moods=${context.moods.topMoods()}, " +
                "fit=%.2f tagFit=%.2f".format(
                    chosen.map { it.corridorFit }.average().takeIf { !it.isNaN() } ?: 1.0,
                    chosen.map { it.tagFit }.average().takeIf { !it.isNaN() } ?: 0.5,
                ),
        )
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
        if (isDiscovery && outcome != ListenOutcome.PLAYED) {
            runCatching {
                feedbackDao.insert(
                    DiscoveryFeedbackEntity(
                        canonicalKey = pick!!.key,
                        artistKey = artistKey,
                        outcome = outcome.name,
                        at = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { Log.w(TAG, "discovery feedback not stored", it) }
        }
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
        val negatives = session.history.asReversed()
            .take(NEGATIVE_WINDOW)
            .filter { it.outcome == ListenOutcome.EARLY_SKIP }
        val tagsById = signals.tagsOf(
            seeds.mapNotNull { it.libraryId } + negatives.mapNotNull { libId(it.trackId) },
        )
        val tagAcc = HashMap<String, Float>()
        for (s in seeds) {
            val id = s.libraryId ?: continue
            tagsById[id]?.forEach { (tag, w) -> tagAcc[tag] = (tagAcc[tag] ?: 0f) + w * s.weight }
        }
        for (n in negatives) {
            val id = libId(n.trackId) ?: continue
            tagsById[id]?.forEach { (tag, w) -> tagAcc[tag] = (tagAcc[tag] ?: 0f) - NEGATIVE_TAG_WEIGHT * w }
        }
        val tagVector = l2Normalize(tagAcc.filterValues { it > 0f })
        // Session moods: what the seeds' tags say, plus what a request asked for.
        val moods = blendMoods(MoodSpace.moodsOf(tagVector), session.requestedMoods)

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

        // Sound of the session: newest heard songs, completed ones count more.
        val heardForAudio = session.history.asReversed()
            .filter { it.outcome != ListenOutcome.EARLY_SKIP }
            .take(SEED_WINDOW)
        val queueForAudio = session.startQueue.asReversed().take(SEED_WINDOW)
        val profiles = audioProfiles(
            (heardForAudio.map { it.trackId } + queueForAudio.map { it.trackId }).filterNotNull(),
        )
        val weighted = buildList {
            heardForAudio.forEachIndexed { i, h ->
                h.trackId?.let(profiles::get)?.let {
                    add(it to (if (h.outcome == ListenOutcome.COMPLETED) 1.2f else 1f) * RECENCY.pow(i))
                }
            }
            if (isEmpty()) {
                queueForAudio.forEachIndexed { i, q ->
                    profiles[q.trackId]?.let { add(it to QUEUE_ONLY_WEIGHT * RECENCY.pow(i)) }
                }
            }
        }

        return AutoplayContext(
            seeds = seeds,
            tagVector = tagVector,
            artistWeights = artistAcc,
            recentArtists = recentArtists,
            lastAudio = weighted.firstOrNull()?.first,
            audioTarget = AudioFlow.blend(weighted),
            moods = moods,
        )
    }

    /**
     * Scores how each library candidate would follow the session by sound:
     * mostly the song just heard (the transition), partly the session's
     * blended sound (so one outlier doesn't steer everything).
     */
    internal suspend fun withFlow(
        context: AutoplayContext,
        candidates: List<AutoplayCandidate>,
        known: Map<Long, AudioProfile>? = null,
    ): List<AutoplayCandidate> {
        val last = context.lastAudio
        val corridor = context.corridor
        if (last == null && corridor == null) return candidates
        val target = context.audioTarget ?: last
        val profiles = known ?: audioProfiles(candidates.mapNotNull { it.track?.id })
        if (profiles.isEmpty()) return candidates
        return candidates.map { c ->
            val p = c.track?.id?.let(profiles::get) ?: return@map c
            c.copy(
                flow = if (last == null || target == null) c.flow
                    else FLOW_LAST * AudioFlow.similarity(last, p) + (1 - FLOW_LAST) * AudioFlow.similarity(target, p),
                corridorFit = corridor?.fit(p) ?: 1f,
            )
        }
    }

    /** Analysed tracks among [ids] → their sound; chunked below SQLite's variable limit. */
    private suspend fun audioProfiles(ids: Collection<Long>): Map<Long, AudioProfile> {
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            ids.distinct().chunked(SQL_CHUNK).flatMap { audioFeatures.getByTrackIds(it) }
                .mapNotNull { e -> e.trackId?.let { it to AudioProfile.of(e) } }
                .toMap()
        }.onFailure { Log.w(TAG, "audio features unavailable", it) }.getOrDefault(emptyMap())
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

        /** Length of a "Mix für mich" and how many recent songs seed it. */
        const val MIX_SIZE = 25
        private const val MIX_SEEDS = 8
        private const val OVER_PROVISION = 3

        const val SEED_WINDOW = 5
        private const val RECENCY = 0.7f
        private const val QUEUE_ONLY_WEIGHT = 0.8f
        private const val NEGATIVE_WINDOW = 10
        private const val NEGATIVE_TAG_WEIGHT = 0.5f

        private const val NEIGHBOUR_SEED_ARTISTS = 3
        private const val NEIGHBOURS_PER_ARTIST = 30
        private const val NEIGHBOUR_DISCOUNT = 0.8f

        /** Library songs fitting the corridor at least this well join the shortlist. */
        private const val CORRIDOR_SHORTLIST_FIT = 0.7f
        private const val TIME_OF_DAY_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

        /** Weight of a request's moods against the seeds' own moods. */
        private const val REQUEST_MOOD_WEIGHT = 2f

        internal fun blendMoods(own: Map<String, Float>, requested: Map<String, Float>): Map<String, Float> {
            if (requested.isEmpty()) return own
            val acc = HashMap(own)
            requested.forEach { (m, w) -> acc[m] = (acc[m] ?: 0f) + REQUEST_MOOD_WEIGHT * w }
            return l2Normalize(acc)
        }

        private fun Map<String, Float>.topMoods(): String =
            entries.sortedByDescending { it.value }.take(2).joinToString("+") { it.key }.ifEmpty { "-" }

        /** Share of `flow` that comes from the song just heard (rest: the session's blend). */
        private const val FLOW_LAST = 0.6f
        private const val SQL_CHUNK = 900

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
