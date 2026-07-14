package com.stash.data.download.export

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmTrackInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NavidromeCoverResolverTest {
    private val lastFmApiClient: LastFmApiClient = mockk()

    @Test
    fun `stored album art path wins without a catalog request`() = runTest {
        val result = resolver(configured = true).resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = "/covers/local.jpg",
            albumArtUrl = "https://example.com/remote.jpg",
            youtubeId = "video123",
        )

        assertThat(result).isEqualTo("/covers/local.jpg")
        coVerify(exactly = 0) { lastFmApiClient.getTrackInfo(any(), any(), any()) }
    }

    @Test
    fun `stored Spotify art is upgraded before export`() = runTest {
        val result = resolver(configured = true).resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = null,
            albumArtUrl = "https://i.scdn.co/image/ab67616d00004851abcdef",
            youtubeId = "video123",
        )

        assertThat(result).isEqualTo("https://i.scdn.co/image/ab67616d0000b273abcdef")
        coVerify(exactly = 0) { lastFmApiClient.getTrackInfo(any(), any(), any()) }
    }

    @Test
    fun `Last fm art wins before the YouTube fallback`() = runTest {
        coEvery { lastFmApiClient.getTrackInfo("Artist", "Title", any()) } returns
            Result.success(trackInfo("https://example.com/lastfm.jpg"))

        val result = resolver(configured = true).resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = null,
            albumArtUrl = null,
            youtubeId = "video123",
        )

        assertThat(result).isEqualTo("https://example.com/lastfm.jpg")
    }

    @Test
    fun `YouTube thumbnail is the final catalog fallback`() = runTest {
        val result = resolver(configured = false).resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = null,
            albumArtUrl = null,
            youtubeId = "video123",
        )

        assertThat(result).isEqualTo("https://i.ytimg.com/vi/video123/sddefault.jpg")
    }

    private fun resolver(configured: Boolean) = NavidromeCoverResolver(
        lastFmApiClient = lastFmApiClient,
        lastFmCredentials = if (configured) {
            LastFmCredentials(apiKey = "key", apiSecret = "secret")
        } else {
            LastFmCredentials(apiKey = "", apiSecret = "")
        },
    )

    private fun trackInfo(bestImageUrl: String?) = LastFmTrackInfo(
        mbid = null,
        durationMs = null,
        listeners = 0,
        playcount = 0,
        userPlaycount = null,
        userLoved = null,
        bestImageUrl = bestImageUrl,
        tags = emptyList(),
    )
}
