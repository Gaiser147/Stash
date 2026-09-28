package com.stash.core.data.autoplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoplayRankerTest {

    private fun lib(artist: String, title: String, sessionSim: Float = 0.5f, recent: Float = 0f, skip: Float = 0f) =
        AutoplayCandidate(
            key = "$artist|$title".lowercase(),
            artist = artist,
            title = title,
            origin = AutoplayOrigin.LIBRARY,
            sessionSim = sessionSim,
            recentPenalty = recent,
            skipPenalty = skip,
        )

    private fun disc(artist: String, title: String, match: Float = 0.8f) = AutoplayCandidate(
        key = "$artist|$title".lowercase(),
        artist = artist,
        title = title,
        origin = AutoplayOrigin.DISCOVERY,
        sessionSim = match,
        lastFmMatch = match,
    )

    private val library = (1..30).map { lib("Artist${it % 6}", "Song$it", sessionSim = it / 30f) }
    private val discovery = (1..30).map { disc("New${it % 6}", "Fresh$it") }

    @Test fun `same seed produces the same batch`() {
        val a = AutoplayRanker.assemble(library, discovery, 10, 0.3f, emptyList(), Random(42))
        val b = AutoplayRanker.assemble(library, discovery, 10, 0.3f, emptyList(), Random(42))
        assertEquals(a, b)
    }

    @Test fun `no artist repeats within the gap`() {
        repeat(20) { seed ->
            val picks = AutoplayRanker.assemble(library, discovery, 12, 0.3f, emptyList(), Random(seed))
            picks.windowed(AutoplayRanker.ARTIST_GAP).forEach { w ->
                assertEquals("gap violated in $w", w.size, w.map { it.artistKey }.toSet().size)
            }
        }
    }

    @Test fun `artist gap is seeded with the songs that just played`() {
        repeat(20) { seed ->
            val picks = AutoplayRanker.assemble(
                library, emptyList(), 1, 0f, recentArtists = listOf("artist1", "artist2"), random = Random(seed),
            )
            assertTrue(picks.single().artistKey !in setOf("artist1", "artist2"))
        }
    }

    @Test fun `gap is relaxed rather than returning a short batch`() {
        val oneArtist = (1..5).map { lib("Solo", "S$it") }
        val picks = AutoplayRanker.assemble(oneArtist, emptyList(), 5, 0f, emptyList(), Random(1))
        assertEquals(5, picks.size)
    }

    @Test fun `zero share means library only`() {
        val picks = AutoplayRanker.assemble(library, discovery, 20, 0f, emptyList(), Random(3))
        assertTrue(picks.all { it.origin == AutoplayOrigin.LIBRARY })
    }

    @Test fun `discoveries are never back to back and the opener is familiar`() {
        repeat(30) { seed ->
            val picks = AutoplayRanker.assemble(
                library, discovery, 12, 1f, emptyList(), Random(seed), openFamiliar = true,
            )
            assertEquals(AutoplayOrigin.LIBRARY, picks.first().origin)
            picks.zipWithNext().forEach { (x, y) ->
                assertTrue(x.origin != AutoplayOrigin.DISCOVERY || y.origin != AutoplayOrigin.DISCOVERY)
            }
            // With share = 1 every other slot is new music.
            assertTrue(picks.count { it.origin == AutoplayOrigin.DISCOVERY } >= 5)
        }
    }

    @Test fun `empty discovery side falls back to library`() {
        val picks = AutoplayRanker.assemble(library, emptyList(), 8, 0.5f, emptyList(), Random(9))
        assertEquals(8, picks.size)
    }

    @Test fun `no duplicates in a batch`() {
        val dupes = library + library
        val picks = AutoplayRanker.assemble(dupes, emptyList(), 25, 0f, emptyList(), Random(5))
        assertEquals(picks.size, picks.map { it.key }.toSet().size)
    }

    @Test fun `penalties push songs down`() {
        val good = lib("A", "good", sessionSim = 0.8f)
        val skipped = lib("B", "skipped", sessionSim = 0.8f, skip = 0.6f)
        val recent = lib("C", "recent", sessionSim = 0.8f, recent = 0.4f)
        assertTrue(AutoplayRanker.score(good) > AutoplayRanker.score(recent))
        assertTrue(AutoplayRanker.score(recent) > AutoplayRanker.score(skipped))
    }

    @Test fun `sampling favours better scores`() {
        val strong = lib("Strong", "s", sessionSim = 1f)
        val weak = (1..9).map { lib("Weak$it", "w$it", sessionSim = 0f) }
        val firsts = (0 until 500).count { seed ->
            AutoplayRanker.sampleOrder(weak + strong, Random(seed)).first() == strong
        }
        // Uniform would be ~50/500; the strong song should lead far more often.
        assertTrue("strong led only $firsts/500", firsts > 300)
    }

    @Test fun `squash maps half point to one half`() {
        assertEquals(0f, AutoplayRanker.squash(0f, 2f), 1e-6f)
        assertEquals(0.5f, AutoplayRanker.squash(2f, 2f), 1e-4f)
        assertTrue(AutoplayRanker.squash(10f, 2f) in 0.9f..1f)
    }
}
