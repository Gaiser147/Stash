package com.stash.core.data.autoplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixArcTest {

    private data class Song(val id: Int, val artist: String, val energy: Float?)

    private fun arrange(songs: List<Song>) =
        MixArc.arrange(songs, energyOf = { it.energy }, artistOf = { it.artist })

    @Test fun `builds up to a peak two thirds in and comes down, opener kept`() {
        val songs = (0 until 20).map { Song(it, "artist$it", energy = ((it * 7) % 20) / 20f) }
        val out = arrange(songs)
        assertEquals(songs.first(), out.first())
        assertEquals(songs.toSet(), out.toSet())
        val energies = out.map { it.energy!! }
        val peakAt = energies.indexOf(energies.drop(1).max())
        assertTrue("peak at $peakAt", peakAt in 10..16)
        // The start is calmer than the peak region, the end calmer than the peak.
        assertTrue(energies.subList(1, 5).average() < energies.subList(11, 15).average())
        assertTrue(energies.takeLast(3).average() < energies.subList(11, 15).average())
    }

    @Test fun `keeps artists apart when it can`() {
        val songs = (0 until 12).map { Song(it, if (it % 2 == 0) "same" else "other$it", energy = it / 12f) }
        val out = arrange(songs)
        out.zipWithNext().forEach { (a, b) ->
            assertTrue("back-to-back ${a.artist}", !(a.artist == "same" && b.artist == "same"))
        }
    }

    @Test fun `a mostly unanalysed mix is left alone`() {
        val songs = (0 until 10).map { Song(it, "a$it", energy = if (it < 4) it / 10f else null) }
        assertEquals(songs, arrange(songs))
    }
}
