package com.stash.core.media.service

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_YOUTUBE_ID
import com.stash.core.media.streaming.stashResolveUri

/**
 * Track eligibility + MediaItem construction for the Android Auto browse
 * tree, shared by every [StashPlaybackService.StashSessionCallback] surface
 * (children listing, shuffle play, browse-tap queueing, search, getItem).
 *
 * Top-level and internal so the exact predicates Android Auto sees are unit
 * testable on the JVM — the car surface can't be manually tested here (no
 * AA hardware; the DHU is dated), so these tests ARE the regression net.
 */

/**
 * Whether a track may appear/play in the car right now.
 *
 * A download always plays. Anything else needs [canStream] (online mode, a
 * network, cellular allowed — see [com.stash.core.media.streaming.StreamingGate]):
 * queuing stream-only songs while streaming is impossible is what made car
 * playback "just stop" — each one failed in turn until the stream-error guard
 * paused the player, with the explanation only visible inside the app.
 *
 * `is_streamable = 0` alone does NOT mean unplayable: the column defaults to
 * 0 meaning "not checked yet" (a background worker drains the checks), so a
 * bare-flag gate silently drops every synced, not-yet-downloaded row — the
 * "playlists open empty in Android Auto" bug, and the same lesson as the
 * v0.9.44 prefetch gate. A row is excluded only when a check CONFIRMED it
 * unstreamable (`is_streamable = 0` with a non-null checked-at) and it has
 * no download — the mirror of `Track.isUnavailableForDisplay`.
 */
internal fun TrackEntity.isPlayableInAuto(canStream: Boolean): Boolean =
    isDownloaded || (canStream && (isStreamable || isStreamableCheckedAt == null))

/** True when the track plays from a file on the phone (no network needed). */
internal fun TrackEntity.isLocalInAuto(): Boolean = isDownloaded && !filePath.isNullOrBlank()

/**
 * Playback URI for a car item: the local file when it's on disk, otherwise a
 * `stash-resolve://` placeholder that [com.stash.core.media.streaming.LazyResolvingDataSource]
 * resolves just-in-time at open(). Replaces the old `filePath ?: ""` pattern,
 * whose empty URI made every stream-only track error at play time (the same
 * empty-URI pathology as the v0.9.44 Liked-Songs skip-storm).
 */
internal fun TrackEntity.autoPlaybackUri(): Uri {
    val path = filePath
    if (isDownloaded && !path.isNullOrBlank()) {
        return if (path.startsWith("/")) "file://$path".toUri() else path.toUri()
    }
    return stashResolveUri(
        trackId = id,
        youtubeId = youtubeId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        isrc = isrc,
    )
}

/**
 * Artwork the car can actually load. Android Auto renders in another process,
 * so a bare file path (`albumArtPath`) never shows; local covers go through
 * [StashArtworkProvider] as `content://` instead. Remote covers stay as-is.
 *
 * @param artAuthority the provider authority; null (tests, no provider) falls
 *   back to the remote cover only.
 */
internal fun TrackEntity.autoArtworkUri(artAuthority: String?): Uri? {
    albumArtUrl?.takeIf { it.startsWith("http") }?.let { return it.toUri() }
    if (artAuthority != null && !albumArtPath.isNullOrBlank()) {
        return StashArtworkProvider.trackArtUri(artAuthority, id)
    }
    return albumArtUrl?.toUri()
}

/** "Artist · Album" — the second line under a song in the car. */
internal fun TrackEntity.autoSubtitle(): String =
    listOf(artist, album).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")

/**
 * Builds the playable MediaItem Android Auto receives for [this] track.
 * Carries the same identity extras as the in-app queue items so downstream
 * consumers (offline silent-skip, scrobbler, notification like, resume)
 * work identically for car-initiated playback.
 *
 * @param mediaId defaults to the track id; browse children pass the
 *   parent-carrying AUTOQ id so a tap can queue the whole playlist.
 * @param artAuthority see [autoArtworkUri].
 */
@OptIn(UnstableApi::class)
internal fun TrackEntity.toAutoMediaItem(
    mediaId: String = id.toString(),
    artAuthority: String? = null,
): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(autoPlaybackUri())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setSubtitle(autoSubtitle())
                .setArtworkUri(autoArtworkUri(artAuthority))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(android.os.Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                    if (durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, durationMs)
                    // "Will stream" — drives the offline silent-skip for
                    // car-queued items exactly like in-app queue items.
                    putBoolean(EXTRA_TRACK_IS_STREAMABLE, !isLocalInAuto())
                    // The car shows a "downloaded" badge on songs that play offline.
                    if (isLocalInAuto()) {
                        putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
                    }
                })
                .build(),
        )
        .build()

/**
 * FTS query for car search / voice: each word as a prefix term. Quotes and
 * FTS operators are stripped so a spoken "AC/DC" or "rock'n'roll" can't
 * break the MATCH syntax.
 */
internal fun ftsQuery(query: String): String =
    query.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { "$it*" }

/** One page of a browse listing; the car pages long lists. */
internal fun <T> pageOf(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0 || pageSize == Int.MAX_VALUE || page < 0) return items
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    return items.subList(from.toInt(), minOf(items.size, from.toInt() + pageSize))
}
