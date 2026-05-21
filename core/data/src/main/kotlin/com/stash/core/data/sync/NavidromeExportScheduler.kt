package com.stash.core.data.sync

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

    suspend fun enqueuePlaylistExport()
}
