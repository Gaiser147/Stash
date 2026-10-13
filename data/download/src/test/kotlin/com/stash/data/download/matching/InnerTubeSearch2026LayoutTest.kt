package com.stash.data.download.matching

import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

/**
 * Since October 2026 YouTube Music returns search rows as
 * `itemSectionRenderer` sections plus a `musicCardShelfRenderer` top result
 * instead of `musicShelfRenderer`. The old parser found nothing, so every
 * query fell through to the slow yt-dlp search. Fixtures are anonymous live
 * responses captured on 2026-10-13.
 */
class InnerTubeSearch2026LayoutTest {

    private fun fixture(name: String) = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("innertube/$name")!!
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun executorFor(name: String): InnerTubeSearchExecutor {
        val inner = mock<InnerTubeClient>()
        val parsed = fixture(name)
        runBlocking { whenever(inner.search(any(), anyOrNull())).thenReturn(parsed) }
        return InnerTubeSearchExecutor(inner)
    }

    @Test
    fun `new item-section layout yields the songs and videos`() = runTest {
        val results = executorFor("search_2026_layout.json").search("Dua Lipa Levitating", maxResults = 20)

        val ids = results.map { it.id }
        assertTrue("official video must be found: $ids", "TUVcZfQe-Kw" in ids)
        assertTrue("lyrics video must be found: $ids", "ovG2QwiObhk" in ids)
        assertEquals("no duplicate ids", ids.size, ids.toSet().size)
        val official = results.first { it.id == "TUVcZfQe-Kw" }
        assertEquals("Levitating (feat. DaBaby)", official.title)
        assertEquals("Dua Lipa", official.uploader)
        assertFalse("view counts are not artists", official.uploader.contains("views"))
    }

    @Test
    fun `episodes podcasts artists and profiles are dropped`() = runTest {
        val results = executorFor("search_2026_layout.json").search("Dua Lipa Levitating", maxResults = 20)

        val ids = results.map { it.id }
        assertFalse("episode must be dropped", "vHFn4gdnpg0" in ids)
        assertFalse("episode must be dropped", "VFwxREatDQ8" in ids)
        assertTrue(results.none { it.title in setOf("Dua Lipa", "DaBaby", "Latest Music News", "Strange Noise Podcast") })
    }

    @Test
    fun `top result card is parsed`() = runTest {
        val results = executorFor("search_2026_layout.json").search("Dua Lipa Levitating", maxResults = 20)

        assertTrue(results.any { it.title.startsWith("Dua Lipa – Levitating (8D AUDIO)") })
    }

    @Test
    fun `message-only response is an empty list`() = runTest {
        assertTrue(executorFor("search_message_only.json").search("x", maxResults = 10).isEmpty())
    }

    @Test
    fun `hybrid search skips yt-dlp when the caller disables the fallback`() = runTest {
        val inner = mock<InnerTubeSearchExecutor>()
        val ytDlp = mock<YouTubeSearchExecutor>()
        whenever(inner.search(any(), any())).thenReturn(emptyList())
        val hybrid = HybridSearchExecutor(inner, ytDlp)

        assertTrue(hybrid.search("q", 10, allowYtDlpFallback = false).isEmpty())
        verify(ytDlp, never()).search(any(), any())
    }

    @Test
    fun `at most two yt-dlp searches run at once`() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val ytDlp = mock<YouTubeSearchExecutor> {
            onBlocking { search(any(), any()) } doSuspendableAnswer {
                peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                release.await()
                running.decrementAndGet()
                emptyList()
            }
        }
        val hybrid = HybridSearchExecutor(mock(), ytDlp)

        val jobs = (1..6).map { async { hybrid.searchYtDlpDirect("q$it") } }
        testScheduler.advanceUntilIdle()
        assertEquals(HybridSearchExecutor.MAX_YTDLP_SEARCHES, running.get())
        release.complete(Unit)
        jobs.awaitAll()
        assertEquals(HybridSearchExecutor.MAX_YTDLP_SEARCHES, peak.get())
    }
}
