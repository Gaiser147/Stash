package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * [InnerTubeClient.searchCanonicalMatch] re-runs the search unfiltered when
 * the Songs filter answers with a bare message (seen since October 2026);
 * this pins the "has rows" check that decides it. Fixtures are anonymous
 * live responses captured on 2026-10-13.
 */
class SearchResultRowsTest {

    private val client = InnerTubeClient(OkHttpClient(), mock<TokenManager>(), mock<YouTubeCookieHelper>())

    private fun fixture(name: String) = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("innertube/$name")!!
            .bufferedReader().use { it.readText() },
    ).jsonObject

    @Test fun `item-section layout has rows`() {
        assertTrue(client.hasResultRows(fixture("search_2026_layout.json")))
    }

    @Test fun `message-only Songs filter response has no rows`() {
        assertFalse(client.hasResultRows(fixture("search_message_only.json")))
    }

    @Test fun `classic shelf layout has rows`() {
        val classic = Json.parseToJsonElement(
            """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{}}]}}]}}}}]}}}""",
        ).jsonObject
        assertTrue(client.hasResultRows(classic))
    }
}
