package com.stash.core.media.service

import androidx.media3.common.MediaItem
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.streaming.STASH_RESOLVE_SCHEME
import kotlin.math.cos
import kotlin.math.sin

/** (outgoing, incoming) gain for ramp progress [t] in [0,1]; equal-power. */
fun equalPowerVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    val rad = c * (Math.PI.toFloat() / 2f)
    return cos(rad) to sin(rad)
}

/** Inputs to the [shouldArm] decision, evaluated each position-poll tick. */
data class ArmInputs(
    val enabled: Boolean,
    val repeatOne: Boolean,
    val hasResolvedNext: Boolean,
    val remainingMs: Long,
    val trackDurationMs: Long,
    val crossfadeMs: Long,
)

/** Arm the fade only when every condition holds (see spec §Trigger). */
fun shouldArm(i: ArmInputs): Boolean =
    i.enabled &&
        !i.repeatOne &&
        i.hasResolvedNext &&
        i.trackDurationMs > 2 * i.crossfadeMs &&
        i.remainingMs in 1..i.crossfadeMs

/**
 * Whether [item] is playable right now (so a fade into it won't error).
 * Local/downloaded items (file/content URIs) always are. A streaming item
 * is only playable once a resolver has produced its URL, which stamps
 * [EXTRA_STREAM_ORIGIN] — placeholder queue-fill http(s) URLs and
 * unresolved `stash-resolve://` items aren't.
 */
internal fun isCrossfadeReady(item: MediaItem): Boolean {
    val scheme = item.localConfiguration?.uri?.scheme?.lowercase() ?: return false
    return when (scheme) {
        "http", "https" -> item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN) != null
        // A stash-resolve:// placeholder still needs a network resolve at
        // open(); priming the spare on it raced the seam, and a failed
        // resolve faded into silence — car playback "just stopped".
        STASH_RESOLVE_SCHEME -> false
        else -> true
    }
}

/** Close enough: master and spare within this play the same instant. */
internal const val SPEED_SYNC_TOLERANCE_MS = 25L

/**
 * Playback speed for the silent master during the hand-off, from
 * [driftMs] = spare position − master position: slow down when the master is
 * ahead, speed up when it's behind, 1.0 once they line up.
 */
internal fun syncSpeed(driftMs: Long): Float = when {
    // A speed change reaches the output ~150 ms late; near the target the
    // gentler rate keeps that lag from overshooting past the tolerance.
    driftMs < -SPEED_SYNC_COARSE_MS -> 0.75f
    driftMs < -SPEED_SYNC_TOLERANCE_MS -> 0.9f
    driftMs > SPEED_SYNC_COARSE_MS -> 1.33f
    driftMs > SPEED_SYNC_TOLERANCE_MS -> 1.1f
    else -> 1f
}

/** Beyond this offset the hand-off syncs at the coarse rate. */
internal const val SPEED_SYNC_COARSE_MS = 200L
