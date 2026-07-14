package com.stash.data.download.acquisition

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.TrackSummary
import org.junit.Test

class MuseAcquisitionMatchScorerTest {
    @Test
    fun `exact artist and title selects unique result`() {
        val expected = track("Teardrop", "Massive Attack", "official")
        val result = MuseAcquisitionMatchScorer.best(
            "Massive Attack - Teardrop",
            listOf(expected, track("Teardrop Live", "José González", "other")),
        )
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `ambiguous title-only query is rejected`() {
        val result = MuseAcquisitionMatchScorer.best(
            "Hello",
            listOf(track("Hello", "Adele", "adele"), track("Hello", "Lionel Richie", "lionel")),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `weak token overlap is rejected`() {
        val result = MuseAcquisitionMatchScorer.best(
            "Boards of Canada Dayvan Cowboy",
            listOf(track("Cowboy Song", "Thin Lizzy", "wrong")),
        )
        assertThat(result).isNull()
    }

    private fun track(title: String, artist: String, id: String) = TrackSummary(
        videoId = id,
        title = title,
        artist = artist,
        album = null,
        durationSeconds = 240.0,
        thumbnailUrl = null,
    )
}
