package com.stash.core.data.autoplay

import com.stash.core.data.db.entity.TrackEntity

/** Where an autoplay pick comes from. */
enum class AutoplayOrigin {
    /** Already in the user's library (downloaded, or a stream-only row). */
    LIBRARY,

    /** Not in the library yet — a Last.fm neighbour, streamed on demand. */
    DISCOVERY,
}

/**
 * One song the autoplay engine could queue next, with the feature values the
 * [AutoplayRanker] scores. Every feature is in [0, 1]; penalties are
 * subtracted.
 *
 * @property key canonical `artist|title` identity (see
 *   [AutoplaySession.keyOf]) used for dedupe across library and discovery.
 * @property track the library row; null for [AutoplayOrigin.DISCOVERY].
 * @property sessionSim how well the song fits what is playing right now
 *   (session tag vector + session artist neighbourhood).
 * @property affinity long-term taste (plays, Last.fm playcount, top artists).
 * @property transition "after these songs you usually play this one"
 *   (listening sequences + shared hand-made playlists). Library only.
 * @property completion how often the user finishes it (0.5 = unknown).
 * @property lastFmMatch Last.fm similar-track/artist match score. Discovery only.
 * @property skipPenalty long-term skip-rate penalty.
 * @property recentPenalty played in the last day — avoid instant repeats.
 */
data class AutoplayCandidate(
    val key: String,
    val artist: String,
    val title: String,
    val origin: AutoplayOrigin,
    val track: TrackEntity? = null,
    val sessionSim: Float = 0f,
    val affinity: Float = 0f,
    val transition: Float = 0f,
    val completion: Float = 0.5f,
    val lastFmMatch: Float = 0f,
    val skipPenalty: Float = 0f,
    val recentPenalty: Float = 0f,
) {
    /** Lowercased artist for spread / session-block comparisons. */
    val artistKey: String get() = artist.trim().lowercase()
}
