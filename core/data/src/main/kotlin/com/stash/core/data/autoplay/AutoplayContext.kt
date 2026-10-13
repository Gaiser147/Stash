package com.stash.core.data.autoplay

/**
 * "Where the listener is right now" — derived fresh from the
 * [AutoplaySession] before every batch so autoplay follows the session as it
 * drifts (skips pull it away, completions pull it closer) instead of staying
 * pinned to the song the queue started with.
 *
 * @property seeds positive seed window, newest first, weights in (0, 1].
 * @property tagVector L2-normalized session tag vector (positives minus
 *   half of the early-skipped songs' tags). Empty when nothing is tagged.
 * @property artistWeights lowercased artist → closeness to the session in
 *   [0, 1]: seed artists by seed weight, Last.fm neighbours by
 *   `seed weight × match`. Session-blocked artists are absent.
 * @property recentArtists lowercased artists of the last songs, oldest first.
 * @property lastAudio sound of the newest heard song with an analysis.
 * @property audioTarget recency-weighted sound of the session (completed
 *   songs count more; early skips don't count). Both null until songs with
 *   server-side audio features have been heard.
 * @property moods session mood weights from the seeds' tags ([MoodSpace]).
 * @property corridor the band of sound to stay in, null when nothing is
 *   known about the session's sound.
 * @property corridorTrackIds library songs whose analysis fits [corridor],
 *   offered to the library shortlist so a calm session finds calm songs even
 *   when its seeds have few neighbours.
 */
data class AutoplayContext(
    val seeds: List<Seed>,
    val tagVector: Map<String, Float>,
    val artistWeights: Map<String, Float>,
    val recentArtists: List<String>,
    val lastAudio: AudioProfile? = null,
    val audioTarget: AudioProfile? = null,
    val moods: Map<String, Float> = emptyMap(),
    val corridor: MoodCorridor? = null,
    val corridorTrackIds: List<Long> = emptyList(),
) {
    /** [libraryId] is the `tracks` row id, null for songs not in the library. */
    data class Seed(
        val libraryId: Long?,
        val artist: String,
        val title: String,
        val weight: Float,
    )

    fun artistWeight(artist: String): Float = artistWeights[artist.trim().lowercase()] ?: 0f
}
