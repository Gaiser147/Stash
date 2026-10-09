package com.stash.data.download.navidrome

import android.util.Log
import com.stash.core.model.Track
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own Navidrome as the first download source: a song the server
 * already has (an earlier upload, the Spotiflac library) comes straight from
 * there — no yt-dlp, no community lossless service, no rate-limit waits.
 * Only active with a Navidrome account set up in Settings.
 */
@Singleton
class NavidromeSource @Inject constructor(
    private val prefs: NavidromeServerPreferences,
    private val client: SubsonicClient,
) {
    /** A song found on the server, ready to fetch. */
    data class Hit(val config: NavidromeServerConfig, val song: SubsonicSong) {
        /** Extension the file gets on the phone (the server's own suffix). */
        val extension: String get() = song.suffix.ifBlank { "mp3" }

        val isLossless: Boolean get() =
            com.stash.data.download.lossless.AudioFormat(extension, song.bitRateKbps).isLossless
    }

    /**
     * After a lookup that couldn't reach the server, skip it for a while:
     * otherwise every song of a sync waits for the connect timeout when the
     * phone is offline or the server is down.
     */
    @Volatile private var unreachableUntilMs = 0L

    /** Null when not configured, the server doesn't have it, or it can't be reached. */
    suspend fun find(track: Track): Hit? {
        val config = prefs.current()
        if (!config.configured || config.passwordDecryptionFailed) return null
        if (System.currentTimeMillis() < unreachableUntilMs) return null
        val song = runCatching {
            client.findSong(config, track.artist, track.title, track.isrc, track.durationMs)
        }.onFailure {
            Log.d(TAG, "own server lookup failed: ${it.message}")
            if (it is java.io.IOException && it !is SubsonicException) {
                unreachableUntilMs = System.currentTimeMillis() + UNREACHABLE_BACKOFF_MS
            }
        }.getOrNull() ?: return null
        if (song.suffix.isNotBlank() && song.suffix !in PLAYABLE_SUFFIXES) return null
        return Hit(config, song)
    }

    /** Fetches [hit] to [destination]; false on any failure (the caller falls through). */
    suspend fun fetch(hit: Hit, destination: File, onProgress: (Long, Long) -> Unit): Boolean =
        runCatching { client.download(hit.config, hit.song.id, destination, onProgress) }
            .onFailure {
                Log.w(TAG, "own server fetch failed: ${it.message}")
                destination.delete()
            }
            .isSuccess && destination.length() > 0

    companion object {
        const val SOURCE_ID = "navidrome"
        private const val UNREACHABLE_BACKOFF_MS = 5 * 60_000L
        private const val TAG = "NavidromeSource"
        private val PLAYABLE_SUFFIXES = setOf("flac", "opus", "ogg", "m4a", "mp3", "aac", "wav")
    }
}
