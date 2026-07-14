package com.stash.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivatePlaylistImportParserTest {
    @Test
    fun `parses Spotify account playlist export without retaining unknown secrets`() {
        val result = PrivatePlaylistImportParser.parse(
            """
                {
                  "accessToken": "must-not-survive",
                  "playlists": [{
                    "name": "Road Trip",
                    "description": "private",
                    "items": [{
                      "track": {
                        "trackName": "Dreams",
                        "artistName": "Fleetwood Mac",
                        "albumName": "Rumours",
                        "trackUri": "spotify:track:0ofHAoxe9vBkTCp2UQIavz"
                      },
                      "addedDate": "2026-01-01"
                    }]
                  }]
                }
            """.trimIndent().toByteArray(),
            "Playlist1.json",
        )

        assertEquals(PrivatePlaylistImportFormat.SPOTIFY_JSON, result.format)
        assertEquals("Road Trip", result.playlists.single().name)
        assertEquals("Dreams", result.playlists.single().tracks.single().title)
        assertEquals("spotify:track:0ofHAoxe9vBkTCp2UQIavz", result.playlists.single().tracks.single().trackUri)
        assertEquals(64, result.playlists.single().snapshotFingerprint.length)
        assertEquals(false, result.toString().contains("must-not-survive"))
    }

    @Test
    fun `parses normalized manifests and conservative metadata`() {
        val result = PrivatePlaylistImportParser.parse(
            """
                {
                  "contract": "muse-private-manifest/v1",
                  "playlists": [{
                    "playlistId": "owned-list-1",
                    "name": "Favorites",
                    "tracks": [{
                      "track_uri": "https://open.spotify.com/track/0ofHAoxe9vBkTCp2UQIavz?si=private",
                      "isrc": "US-WB1-77-00363",
                      "title": "Dreams",
                      "artist": "Fleetwood Mac",
                      "duration_ms": 257000
                    }]
                  }]
                }
            """.trimIndent().toByteArray(),
            "manifest.json",
        )

        val track = result.playlists.single().tracks.single()
        assertEquals(PrivatePlaylistImportFormat.NORMALIZED_JSON, result.format)
        assertEquals("spotify:track:0ofHAoxe9vBkTCp2UQIavz", track.trackUri)
        assertEquals("USWB17700363", track.isrc)
        assertEquals(257000L, track.durationMs)
    }

    @Test
    fun `parses quoted CSV and preserves explicit positions`() {
        val csv = """
            playlist,position,track_uri,title,artist,album,duration_ms
            Mix,2,spotify:track:0ofHAoxe9vBkTCp2UQIavz,Dreams,Fleetwood Mac,Rumours,257000
            Mix,1,,"Song, With Comma",Artist,Album,180000
        """.trimIndent()
        val tracks = PrivatePlaylistImportParser.parse(csv.toByteArray(), "mix.csv")
            .playlists.single().tracks

        assertEquals("Song, With Comma", tracks[0].title)
        assertNull(tracks[0].trackUri)
        assertEquals("Dreams", tracks[1].title)
    }

    @Test
    fun `M3U keeps metadata but never exposes local paths`() {
        val m3u = """
            #EXTM3U
            #PLAYLIST:Offline mix
            #EXTINF:180,Artist - Local Song
            /storage/emulated/0/Music/private.flac
            #EXTINF:257,Fleetwood Mac - Dreams
            spotify:track:0ofHAoxe9vBkTCp2UQIavz
        """.trimIndent()
        val result = PrivatePlaylistImportParser.parse(m3u.toByteArray(), "mix.m3u8")

        assertEquals("Offline mix", result.playlists.single().name)
        assertNull(result.playlists.single().tracks[0].trackUri)
        assertEquals("Local Song", result.playlists.single().tracks[0].title)
        assertEquals(false, result.toString().contains("/storage/"))
    }

    @Test
    fun `rejects ZIP even when disguised as JSON`() {
        val error = assertThrows(PrivatePlaylistImportException::class.java) {
            PrivatePlaylistImportParser.parse(byteArrayOf(0x50, 0x4b, 0x03, 0x04), "export.json")
        }
        assertEquals("zip_not_supported", error.code)
    }
}
