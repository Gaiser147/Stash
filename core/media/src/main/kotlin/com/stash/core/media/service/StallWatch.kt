package com.stash.core.media.service

/**
 * Notices playback that claims to play but doesn't move. Fed one sample per
 * tick; pure so the thresholds are pinned by tests.
 *
 * - READY + playWhenReady with the position frozen for [frozenMs]: the audio
 *   sink or source stalled without an error.
 * - BUFFERING for [bufferingMs]: the stream never came back.
 *
 * The first hit asks for a [Action.REPREPARE] (re-open at the same position),
 * a second one on the same song for [Action.SKIP].
 */
internal class StallWatch(
    private val frozenMs: Long = 4_000,
    private val bufferingMs: Long = 20_000,
) {
    enum class Action { NONE, REPREPARE, SKIP }

    private var lastPos = Long.MIN_VALUE
    private var since = 0L
    private var bufferingSince = -1L
    private var recoveries = 0

    /** A new song or a user action: start over. */
    fun reset() {
        lastPos = Long.MIN_VALUE
        bufferingSince = -1L
        recoveries = 0
    }

    fun sample(
        nowMs: Long,
        playWhenReady: Boolean,
        ready: Boolean,
        buffering: Boolean,
        suppressed: Boolean,
        positionMs: Long,
    ): Action {
        if (!playWhenReady || suppressed || (!ready && !buffering)) {
            lastPos = Long.MIN_VALUE
            bufferingSince = -1L
            return Action.NONE
        }
        val stalled = if (buffering) {
            lastPos = Long.MIN_VALUE
            if (bufferingSince < 0) bufferingSince = nowMs
            nowMs - bufferingSince >= bufferingMs
        } else {
            bufferingSince = -1L
            if (positionMs != lastPos) {
                lastPos = positionMs
                since = nowMs
                false
            } else {
                nowMs - since >= frozenMs
            }
        }
        if (!stalled) return Action.NONE
        lastPos = Long.MIN_VALUE
        bufferingSince = -1L
        recoveries++
        return if (recoveries >= 2) Action.SKIP else Action.REPREPARE
    }
}
