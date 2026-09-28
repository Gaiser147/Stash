package com.stash.core.data.autoplay

import com.stash.core.data.sync.TrackMatcher
import kotlin.random.Random

/** How a song the user heard in this session ended. */
enum class ListenOutcome {
    /** Skipped in the first seconds — a clear "not this". */
    EARLY_SKIP,

    /** Heard for a while, then moved on — mildly positive. */
    PLAYED,

    /** Heard (almost) to the end — a clear "more like this". */
    COMPLETED,
}

/** One song heard in this session, newest last in [AutoplaySession.history]. */
data class HeardSong(
    val trackId: Long?,
    val artist: String,
    val title: String,
    val key: String,
    val origin: AutoplayOrigin?,
    val outcome: ListenOutcome,
)

/**
 * Live, in-memory state of one autoplay run, from the queue the user started
 * to however far autoplay has continued it. Not persisted — a process kill
 * ends the session (already-queued songs still play out), exactly like
 * [com.stash.core.data.radio.RadioSession].
 *
 * Created and mutated only by [AutoplayEngine].
 */
class AutoplaySession internal constructor(
    /** The queue the user started, in order. The seed until songs are heard. */
    internal val startQueue: List<SeedSong>,
    internal val random: Random,
    internal val longTermAcceptance: DiscoveryAcceptance,
) {
    /** A song from the starting queue (library id or synthetic id). */
    data class SeedSong(val trackId: Long, val artist: String, val title: String, val key: String)

    /** Everything heard so far, oldest first. Drives the session context. */
    internal val history: MutableList<HeardSong> = ArrayList()

    /** Identities already queued (start queue + every autoplay pick). No repeats. */
    internal val emittedKeys: MutableSet<String> = startQueue.mapTo(HashSet()) { it.key }

    /** Autoplay picks by the synthetic/library track id the player sees. */
    internal val picksById: MutableMap<Long, AutoplayCandidate> = HashMap()

    /** Artists the user rejected this session (early-skipped discovery, or twice a library song). */
    internal val blockedArtists: MutableSet<String> = HashSet()

    /** Early-skip count per artist this session. */
    internal val artistSkips: MutableMap<String, Int> = HashMap()

    /** This session's discovery evidence, on top of [longTermAcceptance]. */
    internal var sessionAcceptance: DiscoveryAcceptance = DiscoveryAcceptance()

    /** Number of batches produced so far. */
    internal var batches: Int = 0

    /** Combined belief the next batch's discovery share is drawn from. */
    internal val acceptance: DiscoveryAcceptance get() = longTermAcceptance + sessionAcceptance

    companion object {
        /** Canonical `artist|title` identity shared by library rows and discoveries. */
        fun keyOf(matcher: TrackMatcher, artist: String, title: String): String =
            matcher.canonicalArtist(artist) + "|" + matcher.canonicalTitle(title)
    }
}
