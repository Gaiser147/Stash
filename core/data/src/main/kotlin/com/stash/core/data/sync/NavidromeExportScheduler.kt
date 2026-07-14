package com.stash.core.data.sync

/**
 * Cross-module boundary for the optional Stash-to-Navidrome ingest export.
 *
 * The download module owns WorkManager and HTTP details. Core sync workers only
 * signal that a completed file or the current playlist set should be exported.
 */
interface NavidromeExportScheduler {
    suspend fun enqueueTrack(
        filePath: String,
        artist: String,
        album: String?,
        title: String,
        albumArtist: String? = null,
        albumArtUrl: String? = null,
        albumArtPath: String? = null,
        youtubeId: String? = null,
    )

    /** Queue a lightweight playlist-manifest reconciliation. */
    suspend fun enqueuePlaylistExport()

    /** Queue an explicit full file, cover, and playlist reconciliation. */
    suspend fun enqueueFullExport()
}
