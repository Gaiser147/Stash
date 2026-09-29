package com.stash.data.download.navidrome

import com.stash.core.data.sync.TrackMatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SubsonicClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SubsonicClient
    private lateinit var config: NavidromeServerConfig

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = SubsonicClient(OkHttpClient(), TrackMatcher()) { "abc123" }
        config = NavidromeServerConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
            username = "stash",
            password = "sesame",
            scrobbleEnabled = true,
        )
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: String = "") =
        MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","serverVersion":"0.63.2 (x)"$body}}""")

    @Test fun `token auth sends md5 of password plus salt, never the password`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok(""","scanStatus":{"count":2647}"""))

        val info = client.ping(config)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/ping", url.encodedPath)
        assertEquals("stash", url.queryParameter("u"))
        assertEquals("abc123", url.queryParameter("s"))
        assertEquals(SubsonicClient.md5Hex("sesameabc123"), url.queryParameter("t"))
        assertNull(url.queryParameter("p"))
        assertFalse(url.toString().contains("sesame"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals(2647, info.songCount)
        assertTrue(info.serverVersion.startsWith("0.63.2"))
    }

    @Test fun `wrong credentials surface as a permanent Subsonic error`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""",
            ),
        )
        try {
            client.ping(config)
            fail("expected SubsonicException")
        } catch (e: SubsonicException) {
            assertEquals(40, e.code)
            assertTrue(e.isPermanent)
        }
    }

    @Test fun `a server error is retryable, not permanent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        try {
            client.ping(config)
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertFalse(e is SubsonicException)
            assertFalse("credentials must not leak into errors", e.message.orEmpty().contains("abc123"))
        }
    }

    private val searchBody = ""","searchResult3":{"song":[
        {"id":"a","artist":"Linkin Park","title":"Numb (Live)","duration":190,"isrc":[]},
        {"id":"b","artist":"Linkin Park","title":"Numb","duration":187,"isrc":["USWB10302000"]},
        {"id":"c","artist":"Linkin Park","title":"Numb","duration":260,"isrc":[]}
    ]}"""

    @Test fun `ISRC match wins over title matches`() = runTest {
        server.enqueue(ok(searchBody))
        val song = client.findSong(config, "Linkin Park", "Something Else", "uswb10302000", 0)
        assertEquals("b", song?.id)
    }

    @Test fun `without ISRC the canonical title and duration decide`() = runTest {
        server.enqueue(ok(searchBody))
        // 187 s matches "b"; "c" is 260 s and must not be chosen for a 3-minute track.
        val song = client.findSong(config, "Linkin Park", "Numb", null, 185_000)
        assertEquals("b", song?.id)
        assertEquals("Linkin Park Numb", server.takeRequest().requestUrl!!.queryParameter("query"))
    }

    @Test fun `no confident match returns null`() = runTest {
        server.enqueue(ok(searchBody))
        assertNull(client.findSong(config, "Linkin Park", "Numb", null, 400_000))
        server.enqueue(ok(""","searchResult3":{}"""))
        assertNull(client.findSong(config, "Nobody", "Nothing", null, 0))
    }

    @Test fun `a single song object instead of an array still parses`() = runTest {
        server.enqueue(ok(""","searchResult3":{"song":{"id":"z","artist":"Adema","title":"Everyone","duration":209,"isrc":"USAR10100284"}}"""))
        assertEquals("z", client.findSong(config, "Adema", "Everyone", "USAR10100284", 209_000)?.id)
    }

    @Test fun `scrobble sends id, start time and submission flag`() = runTest {
        server.enqueue(ok())
        client.scrobble(config, "b", 1_790_000_000_000L, submission = true)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/scrobble", url.encodedPath)
        assertEquals("b", url.queryParameter("id"))
        assertEquals("1790000000000", url.queryParameter("time"))
        assertEquals("true", url.queryParameter("submission"))
    }

    @Test fun `redact blanks every credential parameter`() {
        val redacted = SubsonicClient.redact("https://x/rest/ping?u=root&t=deadbeef&s=abc&v=1.16.1&p=enc:41")
        assertEquals("https://x/rest/ping?u=***&t=***&s=***&v=1.16.1&p=***", redacted)
    }
}
