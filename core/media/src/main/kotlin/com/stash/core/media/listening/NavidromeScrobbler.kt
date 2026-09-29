package com.stash.core.media.listening

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.PlayerRepository
import com.stash.data.download.navidrome.NavidromeServerConfig
import com.stash.data.download.navidrome.NavidromeServerPreferences
import com.stash.data.download.navidrome.SubsonicClient
import com.stash.data.download.navidrome.SubsonicException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reports what the user listens to in Stash to their own Navidrome server
 * (Subsonic `scrobble`). Navidrome shows it as "now playing", counts the
 * play and forwards it to the Last.fm / ListenBrainz accounts linked in
 * Navidrome, so those services see Stash listening without Stash holding
 * any Last.fm credentials.
 *
 *  - **Now playing:** on every track change, `submission=false`. Best
 *    effort, never retried.
 *  - **Listens:** every `listening_events` row (written by
 *    [ListeningRecorder] once a play crosses the scrobble threshold) is
 *    sent with its original start time and then marked `nd_scrobbled`.
 *    Offline or a failing server leaves the row pending; the queue is
 *    re-drained when plays are added, the account changes, and every
 *    [RETRY_INTERVAL_MS].
 *  - **Songs the server doesn't have yet** (streamed from Qobuz/YouTube,
 *    not uploaded yet) can't be reported through Subsonic. They stay
 *    pending, so once stash-ingest has uploaded them and Navidrome has
 *    scanned them, the listen is reported late but with the right time.
 *    After [GIVE_UP_AFTER_MS] they are dropped.
 *
 * With no account configured, or reporting switched off, pending plays
 * are marked handled so the history isn't replayed later.
 */
@Singleton
class NavidromeScrobbler internal constructor(
    private val playerRepository: PlayerRepository,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val preferences: NavidromeServerPreferences,
    private val client: SubsonicClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Inject constructor(
        playerRepository: PlayerRepository,
        listeningEventDao: ListeningEventDao,
        trackDao: TrackDao,
        preferences: NavidromeServerPreferences,
        client: SubsonicClient,
    ) : this(
        playerRepository, listeningEventDao, trackDao, preferences, client,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis,
    )

    private val drainMutex = Mutex()

    /** trackId → server song id; a cached miss expires so a later upload is picked up. */
    private val matches = ConcurrentHashMap<Long, Match>()

    private data class Match(val songId: String?, val at: Long)

    /** Must be called once from Application.onCreate. */
    fun start() {
        scope.launch {
            playerRepository.playerState
                .distinctUntilChangedBy { it.currentTrack?.id }
                .collect { state ->
                    val track = state.currentTrack ?: return@collect
                    val config = preferences.current()
                    if (!config.configured || !config.scrobbleEnabled) return@collect
                    runCatching {
                        val songId = songIdFor(config, track.id, track.artist, track.title, track.isrc, track.durationMs)
                            ?: return@runCatching
                        client.scrobble(config, songId, clock(), submission = false)
                    }.onFailure { Log.d(TAG, "now-playing report failed: ${it.message}") }
                }
        }
        scope.launch {
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(RETRY_INTERVAL_MS)
                }
            }
            combine(
                preferences.config.distinctUntilChanged(),
                listeningEventDao.pendingNavidromeScrobbleCount().distinctUntilChanged(),
                ticker,
            ) { config, _, _ -> config }
                .collect { drain(it) }
        }
    }

    internal suspend fun drain(config: NavidromeServerConfig) = drainMutex.withLock {
        if (!config.configured || !config.scrobbleEnabled) {
            runCatching { listeningEventDao.markAllNavidromeScrobbled() }
            return@withLock
        }
        val pending = runCatching { listeningEventDao.pendingNavidromeScrobbles(BATCH) }.getOrElse { return@withLock }
        for (event in pending) {
            val track = trackDao.getById(event.trackId)
            if (track == null) {
                listeningEventDao.markNavidromeScrobbled(event.id)
                continue
            }
            val outcome = runCatching {
                val songId = songIdFor(config, track.id, track.artist, track.title, track.isrc, track.durationMs)
                if (songId == null) {
                    // Not on the server (yet). Keep it for a later upload, within limits.
                    if (clock() - event.startedAt > GIVE_UP_AFTER_MS) listeningEventDao.markNavidromeScrobbled(event.id)
                    return@runCatching
                }
                client.scrobble(config, songId, event.startedAt, submission = true)
                listeningEventDao.markNavidromeScrobbled(event.id)
            }
            val error = outcome.exceptionOrNull() ?: continue
            // Wrong credentials or server unreachable: every further row would fail the same way.
            if (error is SubsonicException && error.isPermanent) {
                Log.w(TAG, "Navidrome rejected the account (code ${error.code}); reporting paused")
            } else {
                Log.d(TAG, "Navidrome unreachable, will retry: ${error.message}")
            }
            return@withLock
        }
    }

    private suspend fun songIdFor(
        config: NavidromeServerConfig,
        trackId: Long,
        artist: String,
        title: String,
        isrc: String?,
        durationMs: Long,
    ): String? {
        val now = clock()
        matches[trackId]?.let { cached ->
            if (cached.songId != null || now - cached.at < MISS_TTL_MS) return cached.songId
        }
        val songId = client.findSong(config, artist, title, isrc, durationMs)?.id
        matches[trackId] = Match(songId, now)
        return songId
    }

    companion object {
        private const val TAG = "NavidromeScrobbler"
        private const val BATCH = 50
        internal const val RETRY_INTERVAL_MS = 30L * 60 * 1000
        internal const val MISS_TTL_MS = 30L * 60 * 1000
        internal const val GIVE_UP_AFTER_MS = 14L * 24 * 60 * 60 * 1000
    }
}
