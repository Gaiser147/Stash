package com.stash.data.download.export

import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeCoverResolver @Inject constructor(
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmCredentials: LastFmCredentials,
) {
    private val lastFmCache = ConcurrentHashMap<String, String>()

    suspend fun resolve(track: TrackEntity): String? = resolve(
        artist = track.artist,
        title = track.title,
        albumArtPath = track.albumArtPath,
        albumArtUrl = track.albumArtUrl,
        youtubeId = track.youtubeId,
    )

    suspend fun resolve(
        artist: String,
        title: String,
        albumArtPath: String?,
        albumArtUrl: String?,
        youtubeId: String?,
    ): String? {
        albumArtPath?.takeIf(String::isNotBlank)?.let { return it }
        ArtUrlUpgrader.upgrade(albumArtUrl)?.takeIf(String::isNotBlank)?.let { return it }

        if (lastFmCredentials.isConfigured && artist.isNotBlank() && title.isNotBlank()) {
            val key = "${artist.trim().lowercase()}\n${title.trim().lowercase()}"
            lastFmCache[key]?.takeIf(String::isNotBlank)?.let { return it }
            runCatching { lastFmApiClient.getTrackInfo(artist, title).getOrNull()?.bestImageUrl }
                .getOrNull()
                ?.let(ArtUrlUpgrader::upgrade)
                ?.takeIf(String::isNotBlank)
                ?.let {
                    lastFmCache[key] = it
                    return it
                }
        }

        return youtubeId
            ?.takeIf(String::isNotBlank)
            ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            ?.let(ArtUrlUpgrader::upgrade)
    }
}
