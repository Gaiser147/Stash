package com.stash.core.data.sync

interface NavidromeExportScheduler {
    suspend fun enqueueTrack(
        filePath: String,
        artist: String,
        album: String?,
        title: String,
    )

    suspend fun enqueuePlaylistExport()
}
