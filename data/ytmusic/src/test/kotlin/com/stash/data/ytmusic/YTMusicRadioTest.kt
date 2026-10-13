package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio queue parsing against an anonymous live `next` response for
 * `RDAMVMTUVcZfQe-Kw` (Dua Lipa – Levitating), captured 2026-10-13 and
 * trimmed to the fields the parser reads.
 */
class YTMusicRadioTest {

    private val response = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("innertube/next_radio_2026.json")!!
            .bufferedReader().use { it.readText() },
    ).jsonObject

    @Test fun `radio queue is parsed without the seed`() {
        val tracks = YTMusicRadio.parse(response, seedVideoId = "TUVcZfQe-Kw")

        assertTrue(tracks.size >= 10)
        assertFalse(tracks.any { it.videoId == "TUVcZfQe-Kw" })
        val newRules = tracks.first { it.videoId == "k2qgadSvNyU" }
        assertEquals("New Rules", newRules.title)
        assertEquals("Dua Lipa", newRules.artist)
        assertEquals(225_000L, newRules.durationMs)
        // Anonymous radio responses tag every row as a user upload.
        assertEquals(MusicVideoType.UGC, newRules.musicVideoType)
    }

    @Test fun `video titles lose the artist prefix and official-video decorations`() {
        assertEquals("Hymn For The Weekend", YTMusicRadio.cleanTitle("Coldplay - Hymn For The Weekend (Official Video)", "Coldplay"))
        assertEquals("Kings & Queens", YTMusicRadio.cleanTitle("Ava Max - Kings & Queens [Official Music Video]", "Ava Max"))
        assertEquals(
            "Lean On (feat. MØ)",
            YTMusicRadio.cleanTitle("Major Lazer & DJ Snake - Lean On (feat. MØ) [Official 4K Music Video]", "Major Lazer Official"),
        )
        // A dash that isn't "Artist - Title" stays.
        assertEquals("Shake It Off", YTMusicRadio.cleanTitle("Taylor Swift - Shake It Off", "Taylor Swift"))
        assertEquals("Up - Down", YTMusicRadio.cleanTitle("Up - Down", "Someone Else"))
    }

    @Test fun `channel suffixes are removed from the artist`() {
        assertEquals("Major Lazer", YTMusicRadio.cleanArtist("Major Lazer Official"))
        assertEquals("Dua Lipa", YTMusicRadio.cleanArtist("Dua Lipa - Topic"))
    }
}
