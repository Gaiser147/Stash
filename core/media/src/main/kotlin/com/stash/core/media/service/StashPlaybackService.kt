package com.stash.core.media.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.common.MediaMetadata
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.CrossfadePreference
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.R
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.StashRenderersFactory
import com.stash.core.media.equalizer.computeGain
import com.stash.core.media.PlaybackResumer
import com.stash.core.media.ResumePlayGate
import com.stash.core.media.ResumeStreamResolver
import com.stash.core.media.streaming.PrefetchOrchestrator
import com.stash.core.media.streaming.StashMediaSourceFactory
import com.stash.core.media.streaming.StreamingMediaSourceFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import javax.inject.Inject
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.autoplay.AutoplayEngine
import com.stash.core.data.mapper.toDomain
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.StreamingGate

/**
 * Background playback service that hosts an [ExoPlayer] and exposes a [MediaSession]
 * for media-controller clients (e.g. system notification, Bluetooth, Android Auto).
 *
 * Custom session commands:
 * - [COMMAND_TOGGLE_SHUFFLE] -- toggles shuffle mode on/off
 * - [COMMAND_CYCLE_REPEAT]   -- cycles repeat mode: OFF -> ALL -> ONE -> OFF
 * - [COMMAND_TOGGLE_LIKE]    -- toggles Stash Liked Songs membership for the
 *   currently playing track. Surfaced as a heart icon in the system
 *   notification (expanded view) so the user can like/unlike from the
 *   lockscreen without opening Now Playing.
 */
@AndroidEntryPoint
class StashPlaybackService : MediaLibraryService() {

    @Inject lateinit var eqController: EqController
    @Inject lateinit var loudnessController: LoudnessController
    @Inject lateinit var trackDao: TrackDao
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var likeCoordinator: LikeCoordinator
    @Inject lateinit var prefetchOrchestrator: PrefetchOrchestrator
    @Inject lateinit var streamingMediaSourceFactory: StreamingMediaSourceFactory
    @Inject lateinit var playbackResumer: PlaybackResumer
    @Inject lateinit var resumeStreamResolver: ResumeStreamResolver
    @Inject lateinit var crossfadePreference: CrossfadePreference

    /** Android Auto: "Mix for you" and the shared may-stream rule. */
    @Inject lateinit var autoplayEngine: AutoplayEngine
    @Inject lateinit var streamingGate: StreamingGate

    /**
     * The app's own player front-end. Lazy: it connects a controller to this
     * very service, so it must not be built while the service is being built.
     * Car-started queues are handed to it so autoplay continues them.
     */
    @Inject lateinit var playerRepository: dagger.Lazy<PlayerRepository>

    /** Authority of [StashArtworkProvider] — local covers the car can load. */
    private val artAuthority: String by lazy { StashArtworkProvider.authority(this) }

    /** Deps for the full-timeline lazy-resolve chain (LazyResolvingDataSource). */
    @Inject lateinit var streamResolver: com.stash.core.media.streaming.StreamSourceRegistry
    @Inject lateinit var streamUrlCache: com.stash.core.media.streaming.StreamUrlCache


    /**
     * Shared, interceptor-bearing OkHttp client (carries `AmzCaptchaInterceptor`).
     * Used by [StashMediaSourceFactory] to stream amz-origin items through an
     * authed [androidx.media3.datasource.okhttp.OkHttpDataSource].
     */
    @Inject lateinit var okHttpClient: okhttp3.OkHttpClient

    companion object {
        /** Custom command action for toggling shuffle mode. */
        const val COMMAND_TOGGLE_SHUFFLE = "com.stash.TOGGLE_SHUFFLE"

        /** Custom command action for cycling repeat mode. */
        const val COMMAND_CYCLE_REPEAT = "com.stash.CYCLE_REPEAT"

        /** Custom command action for toggling Stash Liked on the current track. */
        const val COMMAND_TOGGLE_LIKE = "com.stash.TOGGLE_LIKE"

        /** Extra key for the track ID in MediaMetadata extras. */
        const val EXTRA_TRACK_ID = "stash_track_id"

        /**
         * Extra key for the track's YouTube video id. Carried alongside
         * [EXTRA_TRACK_ID] so the notification heart can resolve a
         * v0.9.30 streaming-engine synthetic id to a real DB row via
         * `TrackDao.findByYoutubeId` (issue #105 fix).
         */
        const val EXTRA_TRACK_YOUTUBE_ID = "stash_track_youtube_id"

        /**
         * Extra key for the track's duration in milliseconds. Without
         * this, `MediaItem.toTrack` rehydrates streaming tracks with
         * `durationMs = 0` (the domain default), and `ensureTrackPersisted`
         * then inserts a Liked-Songs row with duration 0:00 (issue #105
         * follow-up: Liked Songs detail showed 0:00 for stream-only tracks).
         */
        const val EXTRA_TRACK_DURATION_MS = "stash_track_duration_ms"

        /**
         * Extra key for the track's `isStreamable` flag. Carried alongside
         * [EXTRA_TRACK_ID] so the auto-advance listener can decide whether
         * to silent-skip a track that the queue would play but the user
         * can't reach (stream-only + offline). v0.9.37.
         */
        const val EXTRA_TRACK_IS_STREAMABLE = "stash_track_is_streamable"

        // ── Streaming-format extras ───────────────────────────────────
        // Written into MediaMetadata.extras when a streaming-only track
        // gets its URL resolved (PlayerRepositoryImpl.buildMediaItemForTrack).
        // Read back by MediaItem.toTrack so the Now Playing screen can
        // show the actual codec/bit-depth/sample-rate Qobuz is serving
        // instead of the stale Room defaults (`fileFormat = "opus"`).
        /** Lowercase codec tag from Qobuz, e.g. `"flac"`, `"mp3"`. */
        const val EXTRA_STREAM_CODEC = "stash_stream_codec"
        /** Bits per sample (16, 24); absent for lossy. */
        const val EXTRA_STREAM_BIT_DEPTH = "stash_stream_bit_depth"
        /** Sample rate in Hz (44100, 96000…). */
        const val EXTRA_STREAM_SAMPLE_RATE = "stash_stream_sample_rate"
        /** Stated bitrate in kbps. */
        const val EXTRA_STREAM_BITRATE = "stash_stream_bitrate"
        /**
         * Resolver origin that produced this stream's URL —
         * `"kennyy"` / `"squid"` (Qobuz lossless) or `"youtube"`
         * (yt-dlp / InnerTube extraction, lossy). Read by Now Playing
         * to surface a "via YT" badge so the user knows when playback
         * has dropped from FLAC to YouTube-extracted audio.
         */
        const val EXTRA_STREAM_ORIGIN = "stash_stream_origin"

        private const val ROOT_ID = "ROOT"
        private const val PLAYLISTS_ID = "PLAYLISTS"
        private const val RECENTLY_ADDED_ID = "RECENTLY_ADDED"
        private const val PLAYLIST_PREFIX = "PLAYLIST_"
        private const val SHUFFLE_PLAY_PREFIX = "SHUFFLE_PLAY_"

        // Android Auto tabs and actions. RECENTLY_ADDED_ID doubles as the
        // "New" tab so AUTOQ ids cached by the car keep resolving.
        private const val FOR_YOU_ID = "FOR_YOU"
        private const val MIX_FOR_ME_ID = "MIX_FOR_ME"
        private const val CONTINUE_ID = "CONTINUE"

        /** Songs in the "New" tab. */
        private const val NEW_LIMIT = 50

        /** Library songs shuffled when "Mix for you" has no history to work from. */
        private const val MIX_FALLBACK_SIZE = 200

        /** Custom command: autoplay "more like this" from the current song. */
        const val COMMAND_MORE_LIKE_THIS = "com.stash.MORE_LIKE_THIS"

        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

        /** Player commands worth a log line when tracking down unexpected stops. */
        private val LOGGED_COMMANDS = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
        )

        /** How often [StallWatch] samples the master. */
        private const val STALL_POLL_MS = 1_000L

        /** Wait after a stream-error halt before continuing with downloads. */
        private const val HALT_SETTLE_MS = 400L

        /**
         * How often the prefetch poll checks playback position against
         * the 60 %-played threshold. 5 s keeps the worst-case prefetch
         * latency below half a poll-interval after crossing the
         * threshold without burning unnecessary CPU on a wakelock-held
         * service. Pulling this lower wastes battery; higher than ~10 s
         * risks crossing the threshold too late on short (<60 s) tracks.
         */
        private const val PREFETCH_POLL_INTERVAL_MS = 5_000L

        /**
         * Crossfade arm-poll cadence. Finer than the prefetch poll so the fade
         * fires within the chosen 1–12 s window before track end; 250 ms keeps
         * worst-case arm jitter to a quarter-second.
         */
        private const val CROSSFADE_POLL_INTERVAL_MS = 250L

        /**
         * Remaining-time threshold at which the spare is primed (once the next
         * track's URL is resolved). Large so cold streams get tens of seconds to
         * buffer before the seam.
         */
        private const val PREPARE_AT_MS = 90_000L

        /** Slack left on the outgoing when the fade ends, so it doesn't hit its natural end. */
        private const val HANDOFF_MARGIN_MS = 500L

        /** Below this much usable fade, skip the crossfade (hard cut). */
        private const val MIN_FADE_MS = 800L
    }

    private var mediaSession: MediaLibrarySession? = null

    // Service-scoped CoroutineScope for the like-state observer + toggle
    // suspending calls. Cancelled in onDestroy so the observer doesn't leak
    // when the service stops.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var likeObserverJob: Job? = null

    /**
     * Periodic position-poll that drives [PrefetchOrchestrator]. Started
     * when playback becomes active, cancelled when it stops or when a
     * track transition occurs (a new poll is started for the next track).
     * Runs on the service's main scope so player reads stay on the
     * required thread.
     */
    private var prefetchPollJob: Job? = null

    /**
     * Two-player crossfade engine (role-swap). Owns players A and B; whichever
     * is master is wired to [mediaSession]. Built in [onCreate].
     */
    private var crossfadeEngine: CrossfadeEngine? = null

    /**
     * Dedicated ~250 ms poll driving crossfade prepare + fire decisions.
     * Separate from [prefetchPollJob] (5 s — too coarse for a 1–12 s seam).
     */
    private var crossfadePollJob: Job? = null

    /** Cached crossfade prefs (off by default); collected in [onCreate]. */
    @Volatile private var crossfadeEnabled = false
    @Volatile private var crossfadeDurationMs = 6000L

    /** mediaId the spare is currently primed for, so we don't re-prepare it. */
    @Volatile private var crossfadePreparedId: String? = null

    /** The player [playerListener] is attached to (the fixed master). */
    private var listenedPlayer: Player? = null

    /**
     * Forces playback to start when a real *play* request restores a queue
     * onto the empty player. Media3 is supposed to auto-play after
     * resumption on a play request, but from a warm process that auto-play
     * sometimes doesn't take and the queue loads paused. Armed in
     * [onPlaybackResumption] and consumed in [onTimelineChanged] once the
     * restored timeline lands. Stays closed for boot-time notification
     * population so the device never starts playing on its own after a
     * reboot. See [ResumePlayGate].
     */
    private val resumePlayGate = ResumePlayGate()

    /** Playing-but-not-moving detector, sampled by [stallWatchJob]. */
    private val stallWatch = StallWatch()
    private var stallWatchJob: Job? = null

    /**
     * The per-track / transport listener, on the session's (fixed) master
     * player.
     */
    @OptIn(UnstableApi::class)
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A user skip (SEEK) or queue change (PLAYLIST_CHANGED) aborts a
            // pending/in-flight crossfade and hard-cuts. The engine's own
            // hand-off seek onto the next song is NOT a skip.
            android.util.Log.i(
                "StashPause",
                "transition reason=$reason to='${mediaItem?.mediaMetadata?.title}' handoff=${crossfadeEngine?.isHandingOff()}",
            )
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                    if (crossfadeEngine?.isHandingOff() != true) {
                        crossfadeEngine?.cancelTransition()
                        crossfadePreparedId = null
                    }
                }
            }
            stallWatch.reset()
            updateCustomLayout()
            onTrackTransitionForLoudness(mediaItem)
            prefetchOrchestrator.resetSession()
            val master = crossfadeEngine?.masterPlayer
            if (master?.isPlaying == true) {
                startPrefetchPoll(master)
                startCrossfadePoll(master)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Who stopped the music: 1 user/controller, 2 audio focus,
            // 3 becoming noisy, 4 remote, 5 end of item, 6 suppressed route.
            val master = crossfadeEngine?.masterPlayer
            android.util.Log.i(
                "StashPause",
                "playWhenReady=$playWhenReady reason=$reason state=${master?.playbackState} " +
                    "pos=${master?.currentPosition} song='${master?.currentMediaItem?.mediaMetadata?.title}'",
            )
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            android.util.Log.i("StashPause", "suppression=$playbackSuppressionReason")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            android.util.Log.i("StashPause", "state=$playbackState")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) clearCarMessage()
            // During a transition the engine owns both players' play/pause state
            // (pauseAtEndOfMediaItems, the spare) — ignore the churn it makes.
            if (crossfadeEngine?.isTransitioning() == true) return
            val master = crossfadeEngine?.masterPlayer
            if (isPlaying && master != null) {
                startPrefetchPoll(master)
                startCrossfadePoll(master)
            } else {
                prefetchPollJob?.cancel(); prefetchPollJob = null
                crossfadePollJob?.cancel(); crossfadePollJob = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            crossfadeEngine?.cancelTransition()
            crossfadePreparedId = null
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val master = crossfadeEngine?.masterPlayer ?: return
            when (resumePlayGate.onTimelineChanged(
                timeline.windowCount,
                master.playbackState == Player.STATE_IDLE,
            )) {
                ResumePlayGate.Action.PREPARE_THEN_PLAY -> { master.prepare(); master.play() }
                ResumePlayGate.Action.PLAY -> master.play()
                ResumePlayGate.Action.NONE -> Unit
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) = updateCustomLayout()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateCustomLayout()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Generate an explicit audio session ID BEFORE building the player.
        // ExoPlayer.audioSessionId returns 0 (global mix) by default until playback starts,
        // which causes audio effect creation to fail with Error -3.
        // By generating our own ID and passing it to the builder, the effects can attach immediately.
        // Generate a dedicated audio session ID so audio effects can attach immediately.
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        val audioSessionId = audioManager.generateAudioSessionId()
        android.util.Log.i("StashPlayback", "Generated audio session ID: $audioSessionId")

        // Route ONLY YouTube-origin streaming items through the refresh chain
        // (RefreshingDataSource → yt-dlp on 403). Background queue-fill seeds
        // the timeline with cheap InnerTube/iOS placeholder URLs that 403 past
        // ~1 MB; without this they surface as onPlayerError and skip-storm the
        // queue. Lossless (Kennyy/Squid) and local/downloaded items stay on the
        // default factory, unchanged.
        val mediaSourceFactory = StashMediaSourceFactory(
            context = this,
            streamingFactory = streamingMediaSourceFactory,
            streamingTrackId = { item ->
                val scheme = item.localConfiguration?.uri?.scheme?.lowercase()
                val origin = item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)
                val trackId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: -1L
                if ((scheme == "http" || scheme == "https") && origin == "youtube" && trackId > 0L) {
                    trackId
                } else {
                    null
                }
            },
            // amz-origin http(s) items stream through an authed OkHttpDataSource
            // (shared client carries AmzCaptchaInterceptor) so the x-captcha-token
            // header rides every range request and is re-minted mid-stream.
            isAmzOrigin = { item ->
                val scheme = item.localConfiguration?.uri?.scheme?.lowercase()
                val origin = item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)
                (scheme == "http" || scheme == "https") && origin == "amz"
            },
            amzHttpClient = okHttpClient,
            // Full-timeline queue: stash-resolve:// placeholders resolve
            // just-in-time inside LazyResolvingDataSource at open().
            resolver = streamResolver,
            urlCache = streamUrlCache,
            trackDao = trackDao,
        )

        // Two-player crossfade engine (role-swap). Both players build with
        // handleAudioFocus = false; the engine manages audio focus manually so
        // a single request covers whichever player is master across swaps.
        val engine = CrossfadeEngine(
            context = this,
            buildPlayer = { buildExoPlayer(mediaSourceFactory, audioAttributes) },
            scope = serviceScope,
        )
        engine.initialize()
        crossfadeEngine = engine
        val player = engine.masterPlayer
        player.audioSessionId = audioSessionId

        // Set session activity so tapping the media notification opens the app.
        // The intent targets the app's launcher activity via the package's launch intent.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                this, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val sessionBuilder = MediaLibrarySession.Builder(this, player, StashSessionCallback())
        if (sessionActivity != null) {
            sessionBuilder.setSessionActivity(sessionActivity)
        }
        val session = sessionBuilder.build()

        mediaSession = session

        // Per-track wiring (heart icon, loudness, prefetch) + transport/resume
        // handling lives in [playerListener], attached to the current master
        // and moved to the new master whenever the crossfade engine swaps.
        player.addListener(playerListener)
        listenedPlayer = player

        // Cache crossfade prefs for the poll's prepare/fire decisions.
        serviceScope.launch { crossfadePreference.enabled.collect { crossfadeEnabled = it } }
        serviceScope.launch { crossfadePreference.durationMs.collect { crossfadeDurationMs = it } }

        updateCustomLayout()

        stallWatchJob = serviceScope.launch {
            while (isActive) {
                delay(STALL_POLL_MS)
                checkStall()
            }
        }

        // Car: keep playing downloaded songs when streaming gives out.
        serviceScope.launch {
            playerRepository.get().streamingHaltedEvents.collect {
                // The guard's pause() travels through the app's controller;
                // let it land first so it can't undo the skip below.
                delay(HALT_SETTLE_MS)
                onStreamingHalted()
            }
        }
    }

    /**
     * Builds an [ExoPlayer] with Stash's renderer/EQ chain, media-source
     * factory and load control. Used for BOTH crossfade players. Audio focus is
     * `false` (the [CrossfadeEngine] manages focus manually so it follows the
     * master across swaps); becoming-noisy + wake stay on so whichever player is
     * master pauses on unplug and holds the wake lock.
     */
    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        mediaSourceFactory: StashMediaSourceFactory,
        audioAttributes: AudioAttributes,
    ): ExoPlayer {
        // Each player gets its OWN LoadControl. Media3 forbids two players
        // sharing a LoadControl unless they also share a playback thread —
        // the spare runs on its own thread, so a shared instance throws
        // IllegalStateException on prepare(). Optimised buffer for local music:
        // large buffers kill storage-I/O micro-stutters; low playback
        // thresholds keep start-up snappy.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()
        return ExoPlayer.Builder(this)
            .setRenderersFactory(StashRenderersFactory(this, eqController, loudnessController))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            // Appended songs (autoplay, add to queue) go after everything
            // queued in shuffle mode, never before the current song.
            .also { it.setShuffleOrder(AppendingShuffleOrder(0)) }
    }

    /**
     * ~250 ms poll driving the crossfade. Each tick [evaluateCrossfade] primes
     * the spare early and fires the fade at the seam. Runs only while the master
     * is playing; the swap callback restarts it against the new master.
     */
    @OptIn(UnstableApi::class)
    private fun startCrossfadePoll(player: Player) {
        crossfadePollJob?.cancel()
        crossfadePollJob = serviceScope.launch {
            while (isActive && player.isPlaying) {
                evaluateCrossfade(player)
                delay(CROSSFADE_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * One crossfade poll tick. Two phases, both no-ops unless crossfade is on
     * and conditions hold:
     *  - **prepare**: well before the seam (`fade + lead`), prime the spare on
     *    the resolved next item so its readiness is never raced at fire time.
     *  - **fire**: inside the fade window, once the spare is buffered, run the
     *    equal-power fade + hand-off via [CrossfadeEngine.performTransition].
     */
    @OptIn(UnstableApi::class)
    private fun evaluateCrossfade(player: Player) {
        val engine = crossfadeEngine ?: return
        if (!crossfadeEnabled || engine.isTransitioning()) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        val duration = player.duration
        if (duration <= 0) return
        val fade = crossfadeDurationMs
        if (duration <= 2 * fade) return // skip very short tracks
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() ?: return
        if (!isCrossfadeReady(nextItem)) return
        val nextId = nextItem.mediaId
        if (nextId == engine.abortedMediaId) return // didn't start last time: hard cut
        val remaining = duration - player.currentPosition

        // Phase 1 — prime the spare as soon as the next track is resolved and we
        // are in the back stretch of the current one, so a COLD stream has tens
        // of seconds to buffer (not just the few seconds before the seam). This
        // is what makes streaming crossfade reliable.
        if (remaining <= PREPARE_AT_MS && !engine.isPreparedFor(nextId)) {
            engine.prepareNext(nextItem)
            crossfadePreparedId = nextId
        }

        // Phase 2 — fire only when the spare has buffered at least the fade
        // length ahead, so it can't stall mid-fade (a barely-READY spare
        // glitches on cold streams).
        if (remaining <= fade && engine.isPreparedFor(nextId) && engine.spareBufferedMs() >= fade) {
            val fadeMs = minOf(fade, remaining - HANDOFF_MARGIN_MS)
            if (fadeMs >= MIN_FADE_MS) {
                crossfadePollJob?.cancel() // restarted once the hand-off is done
                engine.performTransition(fadeMs) { onCrossfadeDone() }
            }
        }
    }

    /**
     * The master plays alone again after a crossfade. The hand-off seek
     * already ran the per-track wiring through [playerListener]; the polls
     * were paused for the fade, so restart them.
     */
    @OptIn(UnstableApi::class)
    private fun onCrossfadeDone() {
        crossfadePreparedId = null
        val master = crossfadeEngine?.masterPlayer ?: return
        if (master.isPlaying) {
            startPrefetchPoll(master)
            startCrossfadePoll(master)
        }
    }

    /**
     * Posts a lightweight "Resuming…" foreground notification so a
     * media-button-triggered resumption (started via startForegroundService
     * on a dead process) satisfies the OS ~5s "must call startForeground"
     * deadline even when the current track needs a slow stream-URL resolve.
     *
     * Reuses Media3's default notification id so the real media notification
     * replaces this placeholder seamlessly once playback starts — no
     * lingering "Resuming…" entry.
     */
    @OptIn(UnstableApi::class)
    private fun showResumingForegroundNotification() {
        val channelId = "stash_playback_resume"
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null
        ) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    getString(R.string.resuming_channel_name),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.resuming_notification_title))
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        val id = androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                id,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(id, notification)
        }
    }

    /**
     * Cancels any existing prefetch poll and starts a new one that ticks
     * every [PREFETCH_POLL_INTERVAL_MS]. Each tick reads the current
     * playback position and the *next* queue item's mediaId off the
     * player on the main thread, then hands those to
     * [PrefetchOrchestrator.onPlaybackProgress] which decides whether to
     * launch a resolve.
     *
     * The poll runs as long as the player reports `isPlaying = true`.
     * `onIsPlayingChanged(false)` cancels it; `onMediaItemTransition`
     * cancels + restarts it so a new "next" target is picked up
     * immediately after auto-advance.
     */
    @OptIn(UnstableApi::class)
    private fun startPrefetchPoll(player: Player) {
        prefetchPollJob?.cancel()
        prefetchPollJob = serviceScope.launch {
            while (isActive && player.isPlaying) {
                val nextIndex = player.nextMediaItemIndex
                val nextId = if (nextIndex == C.INDEX_UNSET) {
                    null
                } else {
                    runCatching { player.getMediaItemAt(nextIndex) }
                        .getOrNull()
                        ?.mediaId
                        ?.toLongOrNull()
                }
                prefetchOrchestrator.onPlaybackProgress(
                    scope = serviceScope,
                    nextTrackId = nextId,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                )
                delay(PREFETCH_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Test-visible per-transition hook for loudness gain. Resolves the
     * media item to a track row (matching the heart-button's
     * `mediaId.toLongOrNull()` convention) and pushes the computed gain
     * to [LoudnessController]. No-ops when the id can't be parsed, the
     * row is missing, or the track has no measured loudness yet — in
     * those cases [computeGain] returns 0 dB which is the safe bypass.
     *
     * Visibility is `internal` so unit tests can invoke the hook directly
     * without booting a full [MediaLibraryService] / [ExoPlayer].
     */
    internal fun onTrackTransitionForLoudness(mediaItem: MediaItem?) {
        val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
        serviceScope.launch {
            val track = trackDao.getById(trackId) ?: return@launch
            val gainDb = computeGain(track.loudnessLufs, track.truePeakDbfs)
            loudnessController.setCurrentTrackGain(gainDb)
        }
    }

    private var lastTrackId: Long? = null
    private var lastIsLiked: Boolean = false

    /**
     * Updates the MediaSession custom layout with the heart, shuffle, and repeat icons.
     * Starts a database observer for the current track's like state. For player-state
     * changes (repeat/shuffle) on the same track, it refreshes the layout using the
     * last known like state to avoid redundant DB observer restarts.
     */
    @OptIn(UnstableApi::class)
    private fun updateCustomLayout() {
        val session = mediaSession ?: return
        val player = session.player
        val mediaItem = player.currentMediaItem
        val trackId = mediaItem?.mediaId?.toLongOrNull()
        val youtubeId = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_TRACK_YOUTUBE_ID)

        if (trackId == null) {
            likeObserverJob?.cancel()
            lastTrackId = null
            lastIsLiked = false
            session.setCustomLayout(ImmutableList.of())
            return
        }

        if (trackId != lastTrackId) {
            likeObserverJob?.cancel()
            lastTrackId = trackId
            // Reset liked state and push an initial layout immediately for the new track.
            // This prevents the previous track's heart state from lingering until the
            // DB observer emits for the first time.
            lastIsLiked = false
            pushLayout(session, player, false)

            likeObserverJob = serviceScope.launch {
                // The id-keyed observer doesn't match streaming-engine
                // synthetic ids; fall back to youtube_id so the heart
                // tracks Room's truth even for stream-only tracks. The
                // service stays in sync with Now Playing's optimistic
                // flip because `MusicRepository.ensureTrackPersisted`
                // writes a row with the same `youtube_id` on like.
                val likeFlow = trackDao.observeLikeState(trackId)
                    .flatMapLatest { state ->
                        if (state != null || youtubeId.isNullOrBlank()) flowOf(state)
                        else trackDao.observeLikeStateByYoutubeId(youtubeId)
                    }
                likeFlow.collect { state ->
                    val isLiked = state?.stashLikedAt != null
                    if (isLiked != lastIsLiked) {
                        lastIsLiked = isLiked
                        pushLayout(session, player, lastIsLiked)
                    }
                }
            }
        } else {
            pushLayout(session, player, lastIsLiked)
        }
    }

    @OptIn(UnstableApi::class)
    private fun pushLayout(session: MediaSession, player: Player, isLiked: Boolean) {
        val layout = ImmutableList.of(
            buildLikeButton(isLiked),
            buildRepeatButton(player.repeatMode)
        )
        session.setCustomLayout(layout)
        // The car gets more room: shuffle and "more like this" as well.
        val carLayout = ImmutableList.of(
            buildLikeButton(isLiked),
            buildShuffleButton(player.shuffleModeEnabled),
            buildRepeatButton(player.repeatMode),
            buildMoreLikeThisButton(),
        )
        session.connectedControllers.filter { isCarController(it) }.forEach { session.setCustomLayout(it, carLayout) }
    }

    @OptIn(UnstableApi::class)
    private fun buildShuffleButton(enabled: Boolean): CommandButton =
        CommandButton.Builder()
            .setDisplayName(getString(if (enabled) R.string.notification_action_shuffle_on else R.string.notification_action_shuffle_off))
            .setIconResId(if (enabled) R.drawable.ic_shuffle else R.drawable.ic_shuffle_off)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_SHUFFLE, android.os.Bundle.EMPTY))
            .build()

    @OptIn(UnstableApi::class)
    private fun buildMoreLikeThisButton(): CommandButton =
        CommandButton.Builder()
            .setDisplayName(getString(R.string.auto_more_like_this))
            .setIconResId(R.drawable.ic_auto_more_like_this)
            .setSessionCommand(SessionCommand(COMMAND_MORE_LIKE_THIS, android.os.Bundle.EMPTY))
            .build()

    /** Android Auto (phone projection) or Android Automotive's media centre. */
    private fun isCarController(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == ANDROID_AUTO_PACKAGE || controller.packageName.startsWith("com.android.car.")

    // ---- Messages in the car ----------------------------------------------

    /** Car controllers currently showing a message from [showCarMessage]. */
    private val carMessageShown = mutableSetOf<MediaSession.ControllerInfo>()

    /**
     * Shows [message] on the car screen. Sent to car controllers only — the
     * app's own controller would treat it as a player error and skip.
     */
    @OptIn(UnstableApi::class)
    private fun showCarMessage(message: String) {
        val session = mediaSession ?: return
        val exception = PlaybackException(message, null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        session.connectedControllers.filter { isCarController(it) }.forEach {
            session.setPlaybackException(it, exception)
            carMessageShown += it
        }
    }

    @OptIn(UnstableApi::class)
    private fun clearCarMessage() {
        val session = mediaSession ?: return
        if (carMessageShown.isEmpty()) return
        carMessageShown.filter { it in session.connectedControllers }.forEach { session.setPlaybackException(it, null) }
        carMessageShown.clear()
    }

    /**
     * The stream-error guard paused playback (streaming keeps failing). In the
     * car that silence used to be all the driver got: carry on with the next
     * downloaded song instead, and say why when there is none.
     */
    private fun onStreamingHalted() {
        val session = mediaSession ?: return
        if (session.connectedControllers.none { isCarController(it) }) return
        val player = session.player
        val next = nextLocalIndex(player)
        android.util.Log.w("StashPlayback", "streaming halted in the car — next local index=$next")
        if (next != null) {
            player.seekTo(next, 0L)
            player.prepare()
            player.play()
        } else {
            showCarMessage(getString(R.string.auto_streaming_unavailable))
        }
    }

    /** Index of the next queue item after the current one that plays from a file. */
    private fun nextLocalIndex(player: Player): Int? {
        var index = player.currentMediaItemIndex
        val seen = HashSet<Int>()
        while (true) {
            index = player.currentTimeline.takeIf { !it.isEmpty }
                ?.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                ?: return null
            if (index == C.INDEX_UNSET || !seen.add(index)) return null
            val scheme = player.getMediaItemAt(index).localConfiguration?.uri?.scheme?.lowercase()
            if (scheme == "file" || scheme == "content") return index
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildRepeatButton(repeatMode: Int): CommandButton {
        val iconRes = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.drawable.ic_repeat_off
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }
        val displayNameRes = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.string.notification_action_repeat_off
            Player.REPEAT_MODE_ALL -> R.string.notification_action_repeat_all
            Player.REPEAT_MODE_ONE -> R.string.notification_action_repeat_one
            else -> R.string.notification_action_repeat_off
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_CYCLE_REPEAT, android.os.Bundle.EMPTY),
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun buildLikeButton(isLiked: Boolean): CommandButton {
        val iconRes = if (isLiked) {
            R.drawable.ic_notification_heart_filled
        } else {
            R.drawable.ic_notification_heart_outlined
        }
        val displayNameRes = if (isLiked) {
            R.string.notification_action_unlike
        } else {
            R.string.notification_action_like
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_TOGGLE_LIKE, android.os.Bundle.EMPTY),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /** One [StallWatch] tick on the master; recovers a frozen player. */
    @OptIn(UnstableApi::class)
    private fun checkStall() {
        val engine = crossfadeEngine ?: return
        if (engine.isTransitioning()) return
        val player = engine.masterPlayer
        val action = stallWatch.sample(
            nowMs = android.os.SystemClock.elapsedRealtime(),
            playWhenReady = player.playWhenReady,
            ready = player.playbackState == Player.STATE_READY,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            positionMs = player.currentPosition,
        )
        if (action == StallWatch.Action.NONE) return
        android.util.Log.w(
            "StashPause",
            "stall: $action state=${player.playbackState} pos=${player.currentPosition} " +
                "song='${player.currentMediaItem?.mediaMetadata?.title}'",
        )
        when (action) {
            StallWatch.Action.REPREPARE -> {
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
                player.prepare()
                player.play()
            }
            StallWatch.Action.SKIP -> if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            StallWatch.Action.NONE -> Unit
        }
    }

    override fun onDestroy() {
        stallWatchJob?.cancel()
        likeObserverJob?.cancel()
        prefetchPollJob?.cancel()
        crossfadePollJob?.cancel()
        listenedPlayer?.removeListener(playerListener)
        listenedPlayer = null
        serviceScope.cancel()
        // Release the session before the players; the engine owns BOTH players.
        mediaSession?.release()
        crossfadeEngine?.release()
        crossfadeEngine = null
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Resolves the Room PK for a Like-toggle from MediaSession state. The
     * `mediaId` may be a v0.9.30 streaming-engine synthetic id
     * (`videoId.hashCode().toLong()`) that doesn't exist in `tracks` —
     * passing it to a FK-bearing write crashes the service. Mirrors the
     * upsert pattern in `MusicRepositoryImpl.ensureTrackPersisted` /
     * `SearchDownloadCoordinator.upsertSearchTrack`.
     *
     * @return the real `tracks.id` to use; never returns a synthetic id.
     * @throws IllegalStateException when no identity info is recoverable
     *   from the MediaItem (i.e. neither a real candidate id nor a
     *   youtubeId / title / artist that maps to one).
     */
    private suspend fun resolveTrackIdForLike(
        candidateId: Long,
        youtubeId: String?,
        metadata: MediaMetadata,
    ): Long {
        // Real Room PK already? Cheap exit.
        if (candidateId > 0L && trackDao.getById(candidateId) != null) return candidateId

        // YouTube id is the most reliable secondary identity.
        if (!youtubeId.isNullOrBlank()) {
            trackDao.findByYoutubeId(youtubeId)?.let { return it.id }
        }

        val title = metadata.title?.toString().orEmpty()
        val artist = metadata.artist?.toString().orEmpty()
        val album = metadata.albumTitle?.toString().orEmpty()
        val albumArtist = metadata.albumArtist?.toString().orEmpty()
        val cTitle = canonicalizeIdentity(title)
        val cArtist = canonicalizeIdentity(artist)

        if (cTitle.isNotBlank() && cArtist.isNotBlank()) {
            trackDao.findByCanonicalIdentity(cTitle, cArtist)?.let { return it.id }
        }

        check(title.isNotBlank() && artist.isNotBlank()) {
            "Cannot resolve track id for like: no youtubeId and no title/artist metadata"
        }

        return trackDao.insert(
            TrackEntity(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                youtubeId = youtubeId,
                canonicalTitle = cTitle,
                canonicalArtist = cArtist,
                source = com.stash.core.model.MusicSource.YOUTUBE,
                isStreamable = true,
                isDownloaded = false,
            )
        )
    }

    private fun canonicalizeIdentity(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---- MediaLibrarySession.Callback ----

    // Content-style hints and per-controller errors are UnstableApi in Media3.
    @OptIn(UnstableApi::class)
    private inner class StashSessionCallback : MediaLibrarySession.Callback {

        private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
            // 1. If it's already a fully resolved item (has URI), use it
            if (item.localConfiguration?.uri != null) {
                return item
            }

            // 2. If it's a library item (has mediaId), resolve it from DB.
            // Downloaded tracks get their file URI; stream tracks get a
            // stash-resolve:// placeholder resolved just-in-time by
            // LazyResolvingDataSource (Android Auto taps never pass through
            // PlayerRepositoryImpl, so nothing is "pre-resolved upstream" —
            // the old absent-URI fallthrough was why car taps didn't play).
            val trackId = item.mediaId.toLongOrNull()
            if (trackId != null) {
                val track = trackDao.getById(trackId)
                if (track != null) {
                    return track.toAutoMediaItem(mediaId = item.mediaId)
                }
            }

            // 3. Fallback to request metadata URI (with security check)
            val uri = item.requestMetadata.mediaUri
            if (uri != null) {
                val scheme = uri.scheme
                if (scheme == "file" || scheme == "android.resource" || scheme == "content") {
                    return item.buildUpon().setUri(uri).build()
                }
            }

            return item
        }

        /**
         * A queue the car (or voice search) asked for: the rows to play, where
         * to start, the shuffle mode to apply, and whether it's "Mix for you".
         */
        private inner class CarQueue(
            val items: List<MediaItem>,
            val tracks: List<com.stash.core.model.Track>,
            val startIndex: Int,
            val shuffle: Boolean?,
            val personalMix: Boolean = false,
        )

        private fun carQueueOf(entities: List<TrackEntity>, startIndex: Int, shuffle: Boolean?): CarQueue? {
            if (entities.isEmpty()) return null
            return CarQueue(
                items = entities.map { it.toAutoMediaItem(artAuthority = artAuthority) },
                tracks = entities.map { it.toDomain() },
                startIndex = startIndex.coerceIn(0, entities.size - 1),
                shuffle = shuffle,
            )
        }

        /** "Mix for you", built like the in-app button; falls back to a library shuffle. */
        private suspend fun mixForYouQueue(canStream: Boolean): CarQueue? {
            val mix = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    autoplayEngine.buildMix(includeStreamable = canStream, allowDiscovery = canStream)
                }
            }.onFailure { android.util.Log.w("StashPlayback", "car mix failed", it) }.getOrDefault(emptyList())
            if (mix.isNotEmpty()) {
                val items = playerRepository.get().queueItemsFor(mix)
                return CarQueue(items, mix, 0, shuffle = false, personalMix = true)
            }
            // No listening history yet: never let the tile do nothing.
            val library = trackDao.getRecentlyAdded(MIX_FALLBACK_SIZE).first()
                .filter { it.isPlayableInAuto(canStream) }
                .shuffled()
            return carQueueOf(library, 0, shuffle = false)
        }

        /** Turns a spoken request into a queue (see [AutoVoiceQuery]). */
        private suspend fun voiceQueue(query: String, canStream: Boolean): CarQueue? {
            val playlists = playlistDao.getAllVisible(includeStreamable = canStream).first()
            return when (val target = AutoVoiceQuery.resolve(query, playlists.map { it.id to it.name })) {
                AutoVoiceQuery.Target.MixForYou -> mixForYouQueue(canStream)
                is AutoVoiceQuery.Target.Playlist -> carQueueOf(
                    playlistDao.getTracksForPlaylist(target.id).filter { it.isPlayableInAuto(canStream) },
                    0,
                    shuffle = false,
                )
                is AutoVoiceQuery.Target.Songs -> carQueueOf(
                    trackDao.searchDownloaded(ftsQuery(target.query)).first(),
                    0,
                    shuffle = false,
                ) ?: mixForYouQueue(canStream)
            }
        }

        /** The queue a browse id stands for, or null when it isn't a car queue id. */
        private suspend fun browseQueue(mediaId: String, canStream: Boolean): CarQueue? {
            if (mediaId == MIX_FOR_ME_ID) return mixForYouQueue(canStream)
            if (mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                val playlistId = mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull() ?: return null
                // isPlayableInAuto, NOT the bare is_streamable flag:
                // never-checked synced rows must play (see AutoBrowse.kt).
                val tracks = playlistDao.getTracksForPlaylist(playlistId)
                    .filter { it.isPlayableInAuto(canStream) }
                    .shuffled()
                return carQueueOf(tracks, 0, shuffle = true)
            }
            // Browse-tap on a playlist/new child (#154/#173): the mediaId
            // carries its parent, so rebuild the whole parent as the queue,
            // starting at the tapped track — in order, unshuffled (shuffle
            // stays reachable via the Shuffle Play entry).
            val parsed = AutoBrowseQueue.parse(mediaId)
            if (parsed == null) {
                // A bare track id (car search result): play it, autoplay continues.
                val track = mediaId.toLongOrNull()?.let { trackDao.getById(it) } ?: return null
                return carQueueOf(listOf(track).filter { it.isPlayableInAuto(canStream) }, 0, shuffle = null)
            }
            val plan = AutoBrowseQueue.queuePlan(
                tracksForBrowseParent(parsed.parentId, canStream),
                tappedTrackId = parsed.trackId,
                canStream = canStream,
            )
            return carQueueOf(plan.tracks, plan.startIndex, shuffle = false)
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                val single = mediaItems.singleOrNull()
                if (single != null && !isOwnController(controller)) {
                    val canStream = streamingGate.canStreamNow()
                    val query = single.requestMetadata.searchQuery
                    if (single.mediaId == CONTINUE_ID) {
                        continueQueue()?.let { return@future it }
                    }
                    val queue = when {
                        single.mediaId.isEmpty() && query != null -> voiceQueue(query, canStream)
                        // Nothing saved to continue: play the mix rather than nothing.
                        single.mediaId == CONTINUE_ID -> mixForYouQueue(canStream)
                        else -> browseQueue(single.mediaId, canStream)
                    }
                    if (queue != null) {
                        android.util.Log.i(
                            "StashPlayback",
                            "car queue from ${controller.packageName}: id=${single.mediaId.take(40)} " +
                                "items=${queue.items.size} mix=${queue.personalMix} canStream=$canStream",
                        )
                        queue.shuffle?.let { shuffle ->
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                mediaSession.player.shuffleModeEnabled = shuffle
                            }
                        }
                        // After the queue reaches the player: autoplay continues
                        // it, exactly as in the app (it used to just end).
                        serviceScope.launch {
                            playerRepository.get().adoptExternalQueue(queue.tracks, queue.personalMix)
                        }
                        return@future MediaSession.MediaItemsWithStartPosition(
                            queue.items,
                            queue.startIndex,
                            C.TIME_UNSET,
                        )
                    }
                    if (query != null || single.mediaId == MIX_FOR_ME_ID || single.mediaId == CONTINUE_ID) {
                        // Asked for something and nothing can play: say so in the car.
                        showCarMessage(getString(R.string.auto_nothing_playable))
                    }
                }
                val resolvedItems = mediaItems.map { resolveMediaItem(it) }
                MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
            }
        }

        /** The in-app controller (PlayerRepositoryImpl) builds its own queues. */
        private fun isOwnController(controller: MediaSession.ControllerInfo): Boolean =
            controller.packageName == packageName && controller.uid == android.os.Process.myUid()

        /** "Continue listening": the persisted queue at the saved position. */
        private suspend fun continueQueue(): MediaSession.MediaItemsWithStartPosition? {
            val plan = playbackResumer.buildResumePlan() ?: return null
            val items = plan.tracks.map { it.toAutoMediaItem(artAuthority = artAuthority) }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                mediaSession?.player?.shuffleModeEnabled = plan.isShuffled
            }
            serviceScope.launch {
                playerRepository.get().adoptExternalQueue(plan.tracks.map { it.toDomain() }, personalMix = false)
            }
            return MediaSession.MediaItemsWithStartPosition(items, plan.startIndex, plan.positionMs)
        }

        /**
         * Loads the track list backing an Auto browse parent id — the same
         * rows (and order) `onGetChildren` listed for it.
         */
        private suspend fun tracksForBrowseParent(parentId: String, canStream: Boolean): List<TrackEntity> =
            when {
                parentId.startsWith(PLAYLIST_PREFIX) ->
                    parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                        ?.let { playlistDao.getTracksForPlaylist(it) }
                        // Same predicate as onGetChildren so the queue built
                        // from a tap matches the rows the car listed.
                        ?.filter { it.isPlayableInAuto(canStream) }
                        ?: emptyList()
                parentId == RECENTLY_ADDED_ID ->
                    trackDao.getRecentlyAdded(NEW_LIMIT).first().filter { it.isPlayableInAuto(canStream) }
                else -> emptyList()
            }

        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            return serviceScope.future {
                if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                    val playlistId = mediaItems[0].mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                    if (playlistId != null) {
                        val canStream = streamingGate.canStreamNow()
                        return@future playlistDao.getTracksForPlaylist(playlistId)
                            .filter { it.isPlayableInAuto(canStream) }
                            .map { it.toAutoMediaItem(artAuthority = artAuthority) }
                            .shuffled()
                    }
                }
                mediaItems.map { item ->
                    // A browse-child id in an ADD context (e.g. "add to queue")
                    // means just that one track — strip the parent envelope and
                    // resolve it as a normal library item. Queue expansion only
                    // happens on a SET (onSetMediaItems above).
                    val parsed = AutoBrowseQueue.parse(item.mediaId)
                    val normalized = if (parsed != null) {
                        item.buildUpon().setMediaId(parsed.trackId.toString()).build()
                    } else {
                        item
                    }
                    resolveMediaItem(normalized)
                }
            }
        }

        // ---- Browse tree -------------------------------------------------

        private fun iconUri(name: String) = "android.resource://$packageName/drawable/$name".toUri()

        private fun styleExtras(browsable: Int? = null, playable: Int? = null, group: String? = null) =
            android.os.Bundle().apply {
                browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
                playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
                group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
            }

        private fun folder(
            id: String,
            title: String,
            subtitle: String? = null,
            artwork: android.net.Uri? = null,
            extras: android.os.Bundle? = null,
        ): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(extras)
                    .build(),
            )
            .build()

        private fun action(
            id: String,
            title: String,
            subtitle: String?,
            artwork: android.net.Uri?,
            extras: android.os.Bundle? = null,
        ): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build(),
            )
            .build()

        private fun rootItem() = folder(ROOT_ID, "Stash")

        private fun tabs(): List<MediaItem> = listOf(
            folder(
                FOR_YOU_ID, getString(R.string.auto_tab_for_you), artwork = iconUri("ic_auto_for_you"),
                extras = styleExtras(
                    browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                ),
            ),
            folder(
                PLAYLISTS_ID, getString(R.string.auto_tab_playlists), artwork = iconUri("ic_auto_playlists"),
                extras = styleExtras(browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
            ),
            folder(
                RECENTLY_ADDED_ID, getString(R.string.auto_tab_new), artwork = iconUri("ic_auto_new"),
                extras = styleExtras(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            ),
        )

        private fun mixForYouItem(group: String?) = action(
            MIX_FOR_ME_ID,
            getString(R.string.auto_mix_for_you),
            getString(R.string.auto_mix_for_you_subtitle),
            iconUri("ic_auto_mix"),
            styleExtras(group = group),
        )

        private fun continueItem(group: String?) = action(
            CONTINUE_ID,
            getString(R.string.auto_continue),
            null,
            iconUri("ic_auto_continue"),
            styleExtras(group = group),
        )

        private fun playlistFolder(playlist: com.stash.core.data.db.entity.PlaylistEntity, group: String?) = folder(
            "$PLAYLIST_PREFIX${playlist.id}",
            playlist.name,
            subtitle = resources.getQuantityString(R.plurals.auto_song_count, playlist.trackCount, playlist.trackCount),
            artwork = playlist.artUrl?.toUri() ?: iconUri(if (AutoBrowseTree.isMix(playlist)) "ic_auto_mix" else "ic_auto_playlists"),
            // Inside a playlist: songs as a list.
            extras = styleExtras(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, group = group),
        )

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            android.util.Log.d("StashPlayback", "onGetItem: id=$mediaId client=${browser.packageName}")
            return serviceScope.future {
                when (mediaId) {
                    ROOT_ID -> return@future LibraryResult.ofItem(rootItem(), null)
                    FOR_YOU_ID, PLAYLISTS_ID, RECENTLY_ADDED_ID ->
                        return@future LibraryResult.ofItem(tabs().first { it.mediaId == mediaId }, null)
                    MIX_FOR_ME_ID -> return@future LibraryResult.ofItem(mixForYouItem(null), null)
                    CONTINUE_ID -> return@future LibraryResult.ofItem(continueItem(null), null)
                }
                // Browse-child ids (AUTOQ_…) resolve to their track,
                // keeping the parent-carrying mediaId intact so a
                // subsequent tap still expands the playlist (#154/#173).
                val trackId = AutoBrowseQueue.parse(mediaId)?.trackId ?: mediaId.toLongOrNull()
                if (trackId != null) {
                    trackDao.getById(trackId)?.let { track ->
                        val item = track.toAutoMediaItem(
                            mediaId = if (mediaId.startsWith(AutoBrowseQueue.PREFIX)) mediaId else track.id.toString(),
                            artAuthority = artAuthority,
                        )
                        grantArtwork(browser, listOf(item))
                        return@future LibraryResult.ofItem(item, null)
                    }
                }
                if (mediaId.startsWith(PLAYLIST_PREFIX)) {
                    mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                        ?.let { playlistDao.getById(it) }
                        ?.let { return@future LibraryResult.ofItem(playlistFolder(it, null), null) }
                }
                if (mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                    val playlistId = mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                    if (playlistId != null && playlistDao.getById(playlistId) != null) {
                        return@future LibraryResult.ofItem(
                            action(mediaId, getString(R.string.shuffle_play), null, iconUri("ic_shuffle")),
                            null,
                        )
                    }
                }
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Defaults for every level: folders as tiles, songs as rows.
            val rootExtras = styleExtras(
                browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            ).apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
            val rootParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .setOffline(params?.isOffline ?: false)
                .setRecent(params?.isRecent ?: false)
                .setSuggested(params?.isSuggested ?: false)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), rootParams))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val canStream = streamingGate.canStreamNow()
                val items: List<MediaItem> = when (parentId) {
                    ROOT_ID -> tabs()
                    FOR_YOU_ID -> {
                        val start = getString(R.string.auto_group_start)
                        val mixes = getString(R.string.auto_group_mixes)
                        val library = getString(R.string.auto_group_library)
                        val playlists = AutoBrowseTree.forYou(playlistDao.getAllVisible(includeStreamable = canStream).first())
                        listOfNotNull(
                            mixForYouItem(start),
                            continueItem(start),
                        ) + playlists.map { playlistFolder(it, if (AutoBrowseTree.isMix(it)) mixes else library) }
                    }
                    PLAYLISTS_ID ->
                        // Stream-only playlists appear only while streaming can work.
                        AutoBrowseTree.library(playlistDao.getAllVisible(includeStreamable = canStream).first())
                            .map { playlistFolder(it, null) }
                    RECENTLY_ADDED_ID ->
                        tracksForBrowseParent(parentId, canStream).map { track ->
                            track.toAutoMediaItem(
                                mediaId = AutoBrowseQueue.childMediaId(parentId, track.id),
                                artAuthority = artAuthority,
                            )
                        }
                    else -> if (parentId.startsWith(PLAYLIST_PREFIX)) {
                        val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                        // isPlayableInAuto, NOT the bare is_streamable flag —
                        // synced rows are "never checked" (is_streamable=0,
                        // checked_at=null) and the bare flag dropped ALL of
                        // them: the "playlist opens empty in the car" bug.
                        // mediaId carries the parent playlist (AUTOQ_…) so a tap
                        // can queue the WHOLE playlist, not a single item — #154/#173.
                        val tracks = tracksForBrowseParent(parentId, canStream).map { track ->
                            track.toAutoMediaItem(
                                mediaId = AutoBrowseQueue.childMediaId(parentId, track.id),
                                artAuthority = artAuthority,
                            )
                        }
                        if (playlistId == null || tracks.isEmpty()) {
                            tracks
                        } else {
                            listOf(
                                action("$SHUFFLE_PLAY_PREFIX$playlistId", getString(R.string.shuffle_play), null, iconUri("ic_shuffle")),
                            ) + tracks
                        }
                    } else {
                        emptyList()
                    }
                }
                grantArtwork(browser, items)
                LibraryResult.ofItemList(ImmutableList.copyOf(pageOf(items, page, pageSize)), params)
            }
        }

        /** Lets the car read the local covers in [items] (it renders in its own process). */
        private fun grantArtwork(browser: MediaSession.ControllerInfo, items: List<MediaItem>) {
            if (browser.packageName == packageName) return
            StashArtworkProvider.grantTo(this@StashPlaybackService, browser.packageName, items.map { it.mediaMetadata.artworkUri })
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 0, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        @OptIn(UnstableApi::class)
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val tracks = trackDao.searchDownloaded(ftsQuery(query)).first()
                val items = tracks.map { it.toAutoMediaItem(artAuthority = artAuthority) }
                grantArtwork(browser, items)
                LibraryResult.ofItemList(ImmutableList.copyOf(pageOf(items, page, pageSize)), params)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val customCommands = listOf(
                SessionCommand(COMMAND_TOGGLE_SHUFFLE, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_CYCLE_REPEAT, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_TOGGLE_LIKE, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_MORE_LIKE_THIS, /* extras = */ android.os.Bundle.EMPTY),
            )
            // FULL library command set — not DEFAULT_SESSION_COMMANDS plus a
            // hand-picked subset. The old hand-picked list omitted
            // COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT (Android Auto could
            // *start* a search but was denied fetching the results — car
            // search showed nothing) and COMMAND_CODE_LIBRARY_UNSUBSCRIBE.
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            customCommands.forEach { sessionCommands.add(it) }

            // Default availablePlayerCommands omits COMMAND_CHANGE_MEDIA_ITEMS,
            // which is what addMediaItem / removeMediaItem / moveMediaItem
            // require. Without explicitly granting full player commands here,
            // controller.addMediaItem(...) silently no-ops — the item never
            // reaches the underlying ExoPlayer's timeline. This is what made
            // "Play Next" and "Add to Queue" appear broken when a queue
            // already existed.
            //
            // NOTE: third-party controllers DO connect to this session —
            // Android Auto (com.google.android.projection.gearhead) and
            // Bluetooth/media-button dispatch are exactly that. Full player
            // commands remain appropriate: everything they can invoke is a
            // standard transport/queue op the app also exposes.
            val playerCommands = Player.Commands.Builder().addAllCommands().build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        @Deprecated("Media3 still routes every player command through here")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            // Which controller paused/stopped/skipped: the car, the app or the
            // notification — the missing piece for "the music just stopped".
            if (playerCommand in LOGGED_COMMANDS) {
                android.util.Log.i(
                    "StashPause",
                    "command=$playerCommand from=${controller.packageName} playWhenReady=${session.player.playWhenReady}",
                )
            }
            if (playerCommand == Player.COMMAND_PLAY_PAUSE || playerCommand == Player.COMMAND_STOP) stallWatch.reset()
            @Suppress("DEPRECATION")
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            // A car that just connected gets its wider button row right away.
            if (isCarController(controller)) {
                android.util.Log.i("StashPlayback", "car connected: ${controller.packageName}")
                updateCustomLayout()
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_TOGGLE_SHUFFLE -> {
                    val player = session.player
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                COMMAND_MORE_LIKE_THIS -> {
                    serviceScope.launch {
                        val ok = runCatching { playerRepository.get().moreLikeThis() }.getOrDefault(false)
                        if (!ok) showCarMessage(getString(R.string.auto_nothing_playable))
                    }
                }
                COMMAND_CYCLE_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                COMMAND_TOGGLE_LIKE -> {
                    val mediaItem = session.player.currentMediaItem
                    val mediaMetadata = mediaItem?.mediaMetadata
                    val candidateId = mediaItem?.mediaId?.toLongOrNull()
                    val youtubeId = mediaMetadata?.extras?.getString(EXTRA_TRACK_YOUTUBE_ID)

                    if (candidateId != null && mediaMetadata != null) {
                        // Optimistic update: toggle the local state and push the layout
                        // immediately so the UI feels snappy and avoids race conditions
                        // where multiple clicks see the same stale DB state.
                        val newLikeState = !lastIsLiked
                        lastIsLiked = newLikeState
                        pushLayout(session, session.player, newLikeState)

                        serviceScope.launch {
                            runCatching {
                                // Resolve the real Room PK. The mediaId may be a
                                // v0.9.30 streaming-engine synthetic id
                                // (`videoId.hashCode().toLong()`); without this
                                // resolve the next call FK-violates on
                                // `tracks.id` and crashes the service process
                                // (issue #105).
                                val realId = resolveTrackIdForLike(
                                    candidateId = candidateId,
                                    youtubeId = youtubeId,
                                    metadata = mediaMetadata,
                                )

                                // v0.9.52: route through LikeCoordinator — local
                                // Stash like stays synchronous; optional Spotify/YT
                                // mirroring (off by default) layers on top.
                                likeCoordinator.setLiked(realId, liked = newLikeState)
                            }.onFailure { e ->
                                android.util.Log.w(
                                    "StashPlayback",
                                    "notification like toggle failed for candidateId=$candidateId yt=$youtubeId",
                                    e,
                                )
                                // Roll back the optimistic flip so the UI reflects truth.
                                lastIsLiked = !newLikeState
                                pushLayout(session, session.player, !newLikeState)
                            }
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Builds a resumable [MediaItem] from a DB row. When [streamUrl] is
         * non-null (the current track was resolved via [ResumeStreamResolver]
         * for online-mode resume), it is used as the playback URI directly.
         * Everything else goes through [toAutoMediaItem]: downloaded rows get
         * their `file://` URI, and stream rows get a `stash-resolve://`
         * placeholder resolved just-in-time at play — instead of the old
         * URI-less item that surfaced as an onPlayerError skip.
         */
        private fun buildResumeItem(track: TrackEntity, streamUrl: String? = null): MediaItem {
            val item = track.toAutoMediaItem()
            return if (streamUrl != null && !(track.isDownloaded && !track.filePath.isNullOrBlank())) {
                item.buildUpon().setUri(streamUrl.toUri()).build()
            } else {
                item
            }
        }

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                // This callback can run for a genuine play request started via
                // startForegroundService (media button on a dead process). The
                // current track may need a (possibly slow) stream-URL resolve
                // for online mode, which can blow the OS 5s "must call
                // startForeground" window. Post a lightweight "Resuming…"
                // foreground notification first to satisfy it; Media3 replaces
                // it with the real media notification once playback starts.
                // Only for a real play request — never for boot-time
                // notification population (isForPlayback = false).
                if (isForPlayback) showResumingForegroundNotification()

                // Preferred path: restore the full persisted queue at the
                // saved track + position so next/prev work and playback
                // continues where it stopped.
                val plan = playbackResumer.buildResumePlan()
                if (plan != null) {
                    // Resolve ONLY the current track's stream URL in the
                    // foreground so it plays in online mode; other streamed
                    // tracks resolve later via the prefetch/skip path.
                    // Downloaded tracks return null here and use their file.
                    val currentStreamUrl = resumeStreamResolver
                        .resolveStreamUrl(plan.tracks[plan.startIndex])
                    val items = plan.tracks.mapIndexed { index, track ->
                        buildResumeItem(
                            track,
                            streamUrl = if (index == plan.startIndex) currentStreamUrl else null,
                        )
                    }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        session.player.shuffleModeEnabled = plan.isShuffled
                    }
                    // Autoplay continues the restored queue (car autostart,
                    // media button) just like one started in the app.
                    serviceScope.launch {
                        playerRepository.get().adoptExternalQueue(plan.tracks.map { it.toDomain() }, personalMix = false)
                    }
                    // Force play once the queue lands iff this is a real play
                    // request (not boot-time notification population).
                    resumePlayGate.arm(isForPlayback)
                    return@future MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(items),
                        plan.startIndex,
                        plan.positionMs,
                    )
                }

                // Fallback (no persisted queue yet): single last-played
                // track, or the most recently added track if none.
                val track = trackDao.getLastPlayedTrack()
                    ?: trackDao.getRecentlyAdded(1).first().firstOrNull()
                val item = track?.let {
                    buildResumeItem(it, streamUrl = resumeStreamResolver.resolveStreamUrl(it))
                }

                if (item != null) {
                    resumePlayGate.arm(isForPlayback)
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(item),
                        /* startIndex= */ 0,
                        /* startPositionMs= */ C.TIME_UNSET,
                    )
                } else {
                    // Nothing to resume (empty library). Drop the "Resuming…"
                    // placeholder we may have posted so it doesn't linger as a
                    // stuck foreground notification, then signal no-resume.
                    if (isForPlayback) {
                        ServiceCompat.stopForeground(
                            this@StashPlaybackService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE,
                        )
                    }
                    throw UnsupportedOperationException()
                }
            }
        }
    }
}
