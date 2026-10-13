package com.stash.core.data.autoplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixRequestParserTest {

    private val library = listOf("Billie Eilish", "Dua Lipa", "Nightcore Zodiac", "AC/DC", "Imagine Dragons")

    private fun parse(text: String) = MixRequestParser.parse(text, library).request

    @Test fun `calm and slow in German`() {
        val r = parse("ruhig und eher langsam")
        assertEquals(setOf("chill"), r.moods)
        assertEquals(105f, r.bpmMax)
        assertTrue(r.energy!!.endInclusive <= 0.45f)
        assertEquals(ArcShape.FLAT, r.arcShape())
    }

    @Test fun `party like a library artist`() {
        val r = parse("Party wie Dua Lipa")
        assertEquals(setOf("party"), r.moods)
        assertEquals(listOf("Dua Lipa"), r.artists)
        assertEquals(ArcShape.PEAK_EARLY, r.arcShape())
    }

    @Test fun `negation and new music`() {
        val r = parse("nicht zu laut, eher neue Songs")
        assertEquals(0.6f, r.energy!!.endInclusive, 0f)
        assertEquals(0.25f, r.familiarity!!, 0f)
    }

    @Test fun `artist typo and unknown artist`() {
        assertEquals(listOf("Billie Eilish"), parse("so wie bilie eilish").artists)
        assertEquals(listOf("Taylor Swift"), parse("like taylor swift").artists)
    }

    @Test fun `artist mentioned without a cue word`() {
        assertEquals(listOf("Imagine Dragons"), parse("imagine dragons zum laufen").artists)
        assertTrue("energetic" in parse("imagine dragons zum laufen").moods)
    }

    @Test fun `genres and synonyms`() {
        val r = parse("deutschrap und lofi zum lernen")
        assertTrue("rap" in r.genres)
        assertTrue("lo-fi" in r.genres)
        assertTrue("focus" in r.moods)
    }

    @Test fun `english focus fast`() {
        val r = parse("fast music for work")
        assertEquals(118f, r.bpmMin)
        assertTrue("focus" in r.moods)
    }

    @Test fun `empty text is an empty request`() {
        assertTrue(parse("").isEmpty)
        assertNull(parse("").corridor())
    }

    @Test fun `mood alone implies a corridor`() {
        val c = MixRequest(moods = setOf("party")).corridor()
        assertNotNull(c)
        assertTrue(c!!.energyCenter >= 0.7f)
    }

    @Test fun `encode and decode round trip`() {
        val r = MixRequest(
            energy = 0.1f..0.4f, bpmMax = 100f, moods = setOf("chill"), genres = setOf("lo-fi"),
            artists = listOf("Billie Eilish"), familiarity = 0.3f, arc = ArcShape.FLAT, size = 50,
        )
        assertEquals(r, MixRequest.decode(r.encode()))
    }
}
