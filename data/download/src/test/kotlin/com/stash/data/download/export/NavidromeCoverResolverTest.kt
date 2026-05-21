package com.stash.data.download.export

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmTrackInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NavidromeCoverResolverTest {
    private val lastFmApiClient: LastFmApiClient = mockk()

    @Test
    fun `stored album art path wins`() = runTest {
        val resolver = resolver(configured = true)

        val result = resolver.resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = "/covers/local.jpg",
            albumArtUrl = "https://example.com/remote.jpg",
            youtubeId = "video123",
        )

        assertEquals("/covers/local.jpg", result)
        coVerify(exactly = 0) { lastFmApiClient.getTrackInfo(any(), any(), any()) }
    }

    @Test
    fun `stored album art url wins and is upgraded`() = runTest {
        val resolver = resolver(configured = true)

        val result = resolver.resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = null,
            albumArtUrl = "https://i.scdn.co/image/ab67616d00004851abcdef",
            youtubeId = "video123",
        )

        assertEquals("https://i.scdn.co/image/ab67616d0000b273abcdef", result)
        coVerify(exactly = 0) { lastFmApiClient.getTrackInfo(any(), any(), any()) }
    }

    @Test
    fun `last fm image wins before youtube fallback`() = runTest {
        coEvery {
            lastFmApiClient.getTrackInfo("Artist", "Title", any())
        } returns Result.success(trackInfo(bestImageUrl = "https://example.com/lastfm.jpg"))
        val resolver = resolver(configured = true)

        val result = resolver.resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = null,
            albumArtUrl = null,
            youtubeId = "video123",
        )

        assertEquals("https://example.com/lastfm.jpg", result)
    }

    @Test
    fun `youtube thumbnail is fallback when catalog art is missing`() = runTest {
        val resolver = resolver(configured = false)

        val result = resolver.resolve(
            artist = "Artist",
            title = "Title",
            albumArtPath = null,
            albumArtUrl = null,
            youtubeId = "video123",
        )

        assertEquals("https://i.ytimg.com/vi/video123/sddefault.jpg", result)
    }

    private fun resolver(configured: Boolean): NavidromeCoverResolver =
        NavidromeCoverResolver(
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
