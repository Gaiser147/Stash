package com.stash.core.data.stats

import com.stash.core.data.autoplay.DiscoveryAcceptance
import com.stash.core.data.autoplay.DiscoveryCandidateSource
import com.stash.core.data.db.dao.AudioFeaturesDao
import com.stash.core.data.db.dao.DiscoveryFeedbackDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.StatsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mix.TrackSignals
import com.stash.core.data.prefs.AutoplayPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Time range of the statistics page. */
enum class StatsRange(val days: Int?) {
    WEEK(7), MONTH(30), YEAR(365), ALL(null);

    fun since(now: Long): Long = days?.let { now - it * DAY_MS } ?: 0L
}

private const val DAY_MS = 86_400_000L

data class TrackStat(val track: TrackEntity, val plays: Int)
data class ArtistStat(val artist: String, val plays: Int)
data class TagStat(val tag: String, val share: Float)

data class ListeningStats(
    val range: StatsRange,
    val listenedMs: Long,
    val plays: Int,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val skips: Int,
    val discoveriesKept: Int,
    /** Listening time per day (last 7/30 days; 30 for year/all), oldest first. */
    val perDay: List<Long>,
    val perHour: List<Long>,
    val weekdayHour: List<List<Long>>,
    val topTracks: List<TrackStat>,
    val topArtists: List<ArtistStat>,
    val topTags: List<TagStat>,
    val sound: StatsAggregator.Sound,
) {
    /** Skips per started song, 0..1. */
    val skipRate: Float get() = if (plays + skips == 0) 0f else skips.toFloat() / (plays + skips)
}

/** What the recommender currently believes about the listener. */
data class TasteProfile(
    val topTags: List<TagStat>,
    val favouriteArtists: List<ArtistStat>,
    /** Expected share of new songs autoplay mixes in, 0..1. */
    val discoveryShare: Float,
    /** Artists autoplay currently doesn't suggest because their new songs kept being skipped. */
    val restingArtists: List<String>,
)

@Singleton
class StatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val audioFeaturesDao: AudioFeaturesDao,
    private val feedbackDao: DiscoveryFeedbackDao,
    private val signals: TrackSignals,
    private val autoplayPreference: AutoplayPreference,
) {

    suspend fun stats(range: StatsRange, now: Long = System.currentTimeMillis()): ListeningStats =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val since = range.since(now)
                val plays = async { statsDao.playsSince(since) }
                val skips = async { statsDao.skipsSince(since) }
                val artists = async { statsDao.distinctArtistsSince(since) }
                val tags = async { statsDao.topTagsSince(since) }
                val kept = async { runCatching { statsDao.discoveriesKeptSince(since) }.getOrDefault(0) }
                val topIds = async { listeningEventDao.topTracksSince(since, limit = 10) }
                val topArtists = async { listeningEventDao.getTopArtistsSince(since, limit = 10) }
                val sound = async { runCatching { audioFeaturesDao.heardSoundSince(since) }.getOrDefault(emptyList()) }

                val rows = plays.await()
                val top = topIds.await()
                val byId = if (top.isEmpty()) emptyMap() else trackDao.getByIds(top.map { it.trackId }).associateBy { it.id }
                val tagRows = tags.await()
                val tagTotal = tagRows.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
                val chartDays = minOf(range.days ?: 30, 30)

                ListeningStats(
                    range = range,
                    listenedMs = rows.sumOf(StatsAggregator::listenedMs),
                    plays = rows.size,
                    distinctSongs = rows.mapTo(HashSet()) { it.trackId }.size,
                    distinctArtists = artists.await(),
                    skips = skips.await(),
                    discoveriesKept = kept.await(),
                    perDay = StatsAggregator.perDay(rows, chartDays, now).toList(),
                    perHour = StatsAggregator.perHour(rows).toList(),
                    weekdayHour = StatsAggregator.weekdayHour(rows).map { it.toList() },
                    topTracks = top.mapNotNull { p -> byId[p.trackId]?.let { TrackStat(it, p.plays) } },
                    topArtists = topArtists.await().map { ArtistStat(it.artist, it.plays) },
                    topTags = tagRows.take(8).map { TagStat(it.tag, it.weight / tagTotal) },
                    sound = StatsAggregator.sound(sound.await()),
                )
            }
        }

    suspend fun taste(now: Long = System.currentTimeMillis()): TasteProfile = withContext(Dispatchers.IO) {
        val vector = runCatching { signals.userTagVector() }.getOrDefault(emptyMap())
        val top = vector.entries.sortedByDescending { it.value }.take(10)
        val total = top.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1e-6f)
        val acceptance = runCatching { autoplayPreference.acceptance() }.getOrDefault(DiscoveryAcceptance())
        val resting = runCatching {
            feedbackDao.artistStatsSince(now - DiscoveryCandidateSource.FEEDBACK_BLOCK_WINDOW_MS)
                .filter { it.earlySkips >= DiscoveryCandidateSource.FEEDBACK_BLOCK_SKIPS && it.completed == 0 }
                .sortedByDescending { it.lastAt }
                .map { it.artistKey }
        }.getOrDefault(emptyList())
        TasteProfile(
            topTags = top.map { TagStat(it.key, it.value / total) },
            favouriteArtists = listeningEventDao.getTopArtistsSince(now - TrackSignals.AFFINITY_WINDOW_MS, limit = 8)
                .map { ArtistStat(it.artist, it.plays) },
            discoveryShare = DiscoveryAcceptance.MAX_SHARE * acceptance.alpha / (acceptance.alpha + acceptance.beta),
            restingArtists = resting,
        )
    }

    /** "Allow again" for an artist autoplay is resting. */
    suspend fun allowArtistAgain(artistKey: String) = withContext(Dispatchers.IO) { feedbackDao.clearArtist(artistKey) }
}
