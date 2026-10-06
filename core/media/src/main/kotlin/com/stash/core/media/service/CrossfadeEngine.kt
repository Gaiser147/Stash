package com.stash.core.media.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Two-player crossfade with a **fixed master**.
 *
 * The [masterPlayer] is the one and only player wired to the MediaSession,
 * for its whole life. The spare only exists to make the overlap audible:
 *  1. [prepareNext] primes the spare with the next item (buffered, paused,
 *     volume 0) well ahead of the fade.
 *  2. [performTransition] starts the spare and runs an equal-power ramp
 *     (master down, spare up).
 *  3. **Hand-off**: at the end of the ramp the master — silent now — seeks to
 *     the next item at the spare's position, buffers while the spare keeps
 *     playing, is re-synced to the spare within its buffer, takes over with a
 *     short micro-fade, and the spare stops.
 *
 * The earlier design swapped which player the session held
 * (`MediaSession.setPlayer`) at the end of every fade. Every controller had
 * to follow the swap — the app, the notification and Android Auto through
 * the legacy session, whose queue ids are timeline indices that the queue
 * transfer shifted. In the car that ended in a paused player right after
 * each crossfade and in appended songs that showed but never played. With a
 * fixed master, every controller always sees the same queue and the same
 * player; the only thing they observe is an ordinary seek to the next song.
 *
 * Audio focus is managed here (both players build with
 * `handleAudioFocus = false`) and follows the master only.
 *
 * Scope is auto-advance only: manual skips [cancelTransition] and hard-cut.
 */
@OptIn(UnstableApi::class)
class CrossfadeEngine(
    context: Context,
    private val buildPlayer: () -> ExoPlayer,
    private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private lateinit var master: ExoPlayer
    private lateinit var spare: ExoPlayer

    /** The player wired to the MediaSession. Never changes. */
    val masterPlayer: ExoPlayer get() = master

    private var transitionJob: Job? = null
    @Volatile private var transitioning = false
    fun isTransitioning(): Boolean = transitioning

    /**
     * True while the master seeks onto the next song itself; the service must
     * not treat that seek as a user skip (which cancels the fade).
     */
    @Volatile private var handingOff = false
    fun isHandingOff(): Boolean = handingOff

    /**
     * The item whose fade was last aborted because it didn't start; the
     * service hard-cuts into it instead of retrying the fade every tick.
     */
    @Volatile var abortedMediaId: String? = null
        private set

    // ── Volumes: fade level × duck level ─────────────────────────────────────
    private var duck = 1f
    private var masterLevel = 1f
    private var spareLevel = 0f

    private fun setLevels(masterLevel: Float, spareLevel: Float) {
        this.masterLevel = masterLevel
        this.spareLevel = spareLevel
        master.volume = masterLevel * duck
        spare.volume = spareLevel * duck
    }

    // ── Audio focus (follows the master) ─────────────────────────────────────
    private var focusRequest: AudioFocusRequest? = null
    private var pausedForFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Every pause the user didn't ask for should be explainable from a log
        // ("the car just stopped"): LOSS = another app took over for good.
        android.util.Log.i("StashFocus", "audio focus change=$change paused=$pausedForFocusLoss transitioning=$transitioning")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                cancelTransition()
                pausedForFocusLoss = false
                master.playWhenReady = false
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                cancelTransition()
                pausedForFocusLoss = true
                master.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duck = DUCK_VOLUME
                setLevels(masterLevel, spareLevel)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                setLevels(masterLevel, spareLevel)
                if (pausedForFocusLoss) {
                    pausedForFocusLoss = false
                    master.playWhenReady = true
                }
            }
        }
    }

    /** Requests focus when the master starts; abandons when it stops. */
    private val masterFocusListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) requestFocus() else if (!pausedForFocusLoss) abandonFocus()
        }
    }

    fun initialize() {
        master = buildPlayer()
        spare = buildPlayer()
        master.addListener(masterFocusListener)
        setLevels(1f, 0f)
    }

    private fun requestFocus() {
        val existing = focusRequest
        if (existing != null) {
            // Play pressed while a transient loss is still pending: ask again
            // instead of assuming we still hold focus.
            if (pausedForFocusLoss) {
                pausedForFocusLoss = false
                audioManager.requestAudioFocus(existing)
            }
            return
        }
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
        } else {
            android.util.Log.w("StashFocus", "audio focus request denied — pausing")
            master.playWhenReady = false
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it); focusRequest = null }
    }

    // ── Spare priming ────────────────────────────────────────────────────────

    /** Prime the spare on [item] (buffered, paused, silent) for an upcoming fade. */
    fun prepareNext(item: MediaItem) {
        spare.stop()
        spare.clearMediaItems()
        spare.playWhenReady = false
        setLevels(masterLevel, 0f)
        spare.setMediaItem(item)
        spare.prepare()
        spare.seekTo(0)
    }

    /** Whether the spare has buffered enough to start the fade. */
    fun isNextReady(): Boolean =
        ::spare.isInitialized && spare.playbackState == Player.STATE_READY

    /** Diagnostics: the spare's current playbackState and primed item. */
    fun spareState(): Int = if (::spare.isInitialized) spare.playbackState else -1
    fun spareId(): String? = if (::spare.isInitialized) spare.currentMediaItem?.mediaId else null

    /**
     * How much the spare has buffered from its start (it is primed at position
     * 0). Used to gate the fire so we only crossfade into a spare that has at
     * least the fade length ready — a barely-READY spare stalls mid-fade on
     * cold streams.
     */
    fun spareBufferedMs(): Long =
        if (::spare.isInitialized) spare.bufferedPosition.coerceAtLeast(0) else 0

    /** True when the spare is primed with [mediaId] (so we don't re-prepare it). */
    fun isPreparedFor(mediaId: String?): Boolean =
        mediaId != null && ::spare.isInitialized &&
            spare.mediaItemCount > 0 && spare.currentMediaItem?.mediaId == mediaId

    // ── The fade and the hand-off ────────────────────────────────────────────

    /**
     * Runs the crossfade into the primed spare, then hands playback back to
     * the master on the next item (see the class doc). [fadeMs] is the ramp
     * length; [onDone] runs on the main thread once the master plays alone
     * again. No-ops if nothing is primed.
     */
    fun performTransition(fadeMs: Long, onDone: () -> Unit) {
        if (transitioning || !::spare.isInitialized || spare.mediaItemCount == 0) return
        val nextId = spare.currentMediaItem?.mediaId ?: return
        transitioning = true
        transitionJob = scope.launch {
            setLevels(1f, 0f)
            spare.playWhenReady = true
            spare.play()
            // Wait for the incoming to actually produce audio (bounded).
            var w = 0L
            while (!spare.isPlaying && w < START_TIMEOUT_MS) { delay(STEP_MS); w += STEP_MS }
            if (!spare.isPlaying) {
                // The next song didn't start (stream not reachable, decoder
                // error). Fading into it would leave silence; abort and let
                // the current song end and advance normally (a hard cut).
                android.util.Log.w("Crossfade", "incoming did not start within ${START_TIMEOUT_MS}ms — aborting fade")
                abortedMediaId = nextId
                finish()
                return@launch
            }

            var elapsed = 0L
            while (elapsed < fadeMs) {
                val (out, inc) = equalPowerVolumes(elapsed.toFloat() / fadeMs)
                setLevels(out, inc)
                if (spare.playbackState == Player.STATE_ENDED) break
                delay(STEP_MS)
                elapsed += STEP_MS
            }
            setLevels(0f, 1f)
            handOff(nextId)
            finish()
            onDone()
        }
    }

    /** Moves playback of [nextId] from the spare back onto the (silent) master. */
    private suspend fun handOff(nextId: String) {
        val index = handoffIndex(nextId)
        if (index == null) {
            // The queue changed under the fade; the master advances on its own.
            android.util.Log.w("Crossfade", "hand-off: $nextId no longer in the queue — master continues")
            return
        }
        handingOff = true
        try {
            master.seekTo(index, spare.currentPosition + HANDOFF_LEAD_MS)
            master.playWhenReady = true
            // The spare keeps playing (audible) while the master buffers.
            if (!waitForMaster(index, HANDOFF_TIMEOUT_MS)) {
                android.util.Log.w("Crossfade", "hand-off: master not playing after ${HANDOFF_TIMEOUT_MS}ms — hard switch")
                return
            }
            // Re-sync inside the master's buffer so the switch is inaudible.
            val drift = handoffDrift(spareMs = spare.currentPosition, masterMs = master.currentPosition)
            if (drift != null && spare.playbackState != Player.STATE_ENDED) {
                master.seekTo(index, spare.currentPosition + RESYNC_LEAD_MS)
                waitForMaster(index, RESYNC_TIMEOUT_MS)
            }
            // Micro-fade spare → master.
            var t = 0L
            while (t < MICRO_FADE_MS) {
                val (out, inc) = equalPowerVolumes(t.toFloat() / MICRO_FADE_MS)
                setLevels(inc, out)
                delay(STEP_MS / 2)
                t += STEP_MS / 2
            }
        } finally {
            handingOff = false
        }
    }

    /**
     * Where [nextId] sits on the master: the up-next slot, or the current one
     * when the master already reached the end and advanced on its own.
     */
    private fun handoffIndex(nextId: String): Int? {
        val next = master.nextMediaItemIndex
        if (next != androidx.media3.common.C.INDEX_UNSET && master.getMediaItemAt(next).mediaId == nextId) return next
        if (master.currentMediaItem?.mediaId == nextId) return master.currentMediaItemIndex
        return null
    }

    private suspend fun waitForMaster(index: Int, timeoutMs: Long): Boolean {
        var w = 0L
        while (w < timeoutMs) {
            if (master.isPlaying && master.currentMediaItemIndex == index) return true
            if (spare.playbackState == Player.STATE_ENDED) return master.currentMediaItemIndex == index
            delay(STEP_MS / 2)
            w += STEP_MS / 2
        }
        return false
    }

    /** Master alone and audible again; the spare reset to a clean silent state. */
    private fun finish() {
        setLevels(1f, 0f)
        spare.playWhenReady = false
        spare.stop()
        spare.clearMediaItems()
        handingOff = false
        transitioning = false
        transitionJob = null
    }

    /** Abort a pending/in-flight fade and restore the master; spare is reset. */
    fun cancelTransition() {
        transitionJob?.cancel()
        transitionJob = null
        if (::master.isInitialized && ::spare.isInitialized) finish()
    }

    fun release() {
        transitionJob?.cancel()
        abandonFocus()
        if (::master.isInitialized) {
            master.removeListener(masterFocusListener)
            master.release()
        }
        if (::spare.isInitialized) spare.release()
    }

    private companion object {
        const val STEP_MS = 50L
        const val START_TIMEOUT_MS = 1000L
        const val DUCK_VOLUME = 0.2f
        const val MICRO_FADE_MS = 120L

        /** The master seeks slightly ahead of the spare: it has to buffer first. */
        const val HANDOFF_LEAD_MS = 300L
        /** Give a stream this long to start on the master; the spare covers it. */
        const val HANDOFF_TIMEOUT_MS = 15_000L
        const val RESYNC_LEAD_MS = 20L
        const val RESYNC_TIMEOUT_MS = 2_000L
    }
}
