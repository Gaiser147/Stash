package com.stash.core.data.autoplay

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.mix.TrackSignals
import com.stash.core.data.prefs.AutoplayPreference
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.Track
import com.stash.data.ytmusic.CanonicalMatch
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoplayEngineTest {

    private val trackDao: TrackDao = mockk()
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val signals: TrackSignals = mockk(relaxed = true)
    private val library: LibraryCandidateSource = mockk()
    private val discovery: DiscoveryCandidateSource = mockk()
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private val preference: AutoplayPreference = mockk(relaxed = true)
    private val matcher = TrackMatcher()

    private val engine = AutoplayEngine(
        trackDao, listeningEventDao, signals, library, discovery, lastFm, yt, matcher, preference,
    )

    private val libraryRows = (1L..12L).map {
        TrackEntity(id = it, title = "Song $it", artist = "Artist ${it % 4}", isDownloaded = true)
    }

    init {
        coEvery { trackDao.getAllPlayable(any()) } returns libraryRows
        coEvery { preference.acceptance() } returns DiscoveryAcceptance()
        coEvery { signals.tagsOf(any()) } returns emptyMap()
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(emptyList())
        coEvery { library.candidates(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val pool = secondArg<List<TrackEntity>>()
            val keyOf = thirdArg<(TrackEntity) -> String>()
            pool.map { AutoplayCandidate(keyOf(it), it.artist, it.title, AutoplayOrigin.LIBRARY, track = it, sessionSim = 0.5f) }
        }
        coEvery { discovery.candidates(any(), any(), any(), any(), any()) } returns
            (1..10).map {
                AutoplayCandidate("new $it|fresh $it", "New $it", "Fresh $it", AutoplayOrigin.DISCOVERY, lastFmMatch = 0.9f)
            }
        coEvery { yt.searchCanonicalMatch(any(), any()) } answers {
            CanonicalMatch(videoId = "vid-${secondArg<String>()}", thumbnailUrl = null)
        }
    }

    private fun queueTrack(id: Long) = Track(id = id, title = "Song $id", artist = "Artist ${id % 4}")

    @Test fun `batches never repeat the starting queue or each other`() = runTest {
        val session = engine.start(listOf(queueTrack(1), queueTrack(2)), Random(1))
        val first = engine.nextBatch(session, includeStreamable = true, allowDiscovery = true)
        val second = engine.nextBatch(session, includeStreamable = true, allowDiscovery = true)

        val keys = (first + second).map { "${it.artist}|${it.title}" }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue((first + second).none { it.id == 1L || it.id == 2L })
        assertEquals(AutoplayEngine.BATCH_SIZE, first.size)
    }

    @Test fun `offline batches never ask for discoveries`() = runTest {
        val session = engine.start(listOf(queueTrack(1)), Random(2))
        val batch = engine.nextBatch(session, includeStreamable = false, allowDiscovery = false)

        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.id in 1L..12L })
        coVerify(exactly = 0) { discovery.candidates(any(), any(), any(), any(), any()) }
    }

    @Test fun `first batch opens with a familiar song`() = runTest {
        repeat(10) { seed ->
            val session = engine.start(listOf(queueTrack(1)), Random(seed))
            val batch = engine.nextBatch(session, includeStreamable = true, allowDiscovery = true)
            assertTrue("seed $seed opened with ${batch.first()}", batch.first().id in 1L..12L)
        }
    }

    @Test fun `early skip of a discovery blocks its artist and records a failure`() = runTest {
        // Force the discovery share high so the batch contains new music.
        coEvery { preference.acceptance() } returns DiscoveryAcceptance(alpha = 50f, beta = 0.1f)
        val session = engine.start(listOf(queueTrack(1)), Random(3))
        var newSong: Track? = null
        repeat(5) {
            if (newSong == null) {
                newSong = engine.nextBatch(session, includeStreamable = true, allowDiscovery = true)
                    .firstOrNull { it.youtubeId != null }
            }
        }
        val song = checkNotNull(newSong) { "no discovery in five batches at ~50% share" }

        engine.recordOutcome(session, song, listenedMs = 5_000, durationMs = 200_000)

        assertTrue(song.artist.lowercase() in session.blockedArtists)
        coVerify { preference.updateAcceptance(any()) }

        val blocked = mutableListOf<Set<String>>()
        engine.nextBatch(session, includeStreamable = true, allowDiscovery = true)
        coVerify { discovery.candidates(any(), any(), capture(blocked), any(), any()) }
        assertTrue(song.artist.lowercase() in blocked.last())
    }

    @Test fun `library artist is blocked only after two early skips`() = runTest {
        val session = engine.start(listOf(queueTrack(1)), Random(4))
        engine.recordOutcome(session, queueTrack(5), listenedMs = 3_000, durationMs = 180_000)
        assertTrue(session.blockedArtists.isEmpty())
        engine.recordOutcome(session, queueTrack(9), listenedMs = 3_000, durationMs = 180_000)
        assertEquals(setOf("artist 1"), session.blockedArtists)

        val pool = slot<List<TrackEntity>>()
        engine.nextBatch(session, includeStreamable = true, allowDiscovery = false)
        coVerify { library.candidates(any(), capture(pool), any(), any()) }
        assertTrue(pool.captured.none { it.artist == "Artist 1" })
    }

    @Test fun `context follows what was heard, newest first, without skips`() = runTest {
        val session = engine.start(listOf(queueTrack(1), queueTrack(2), queueTrack(3)), Random(5))
        engine.recordOutcome(session, queueTrack(1), listenedMs = 190_000, durationMs = 200_000)
        engine.recordOutcome(session, queueTrack(2), listenedMs = 4_000, durationMs = 200_000)

        val ctx = engine.buildContext(session, libraryIds = (1L..12L).toSet(), online = false)

        assertEquals("Song 1", ctx.seeds.first().title)
        assertTrue(ctx.seeds.none { it.title == "Song 2" })
        // Unheard queue songs pad the window at a lower weight.
        assertTrue(ctx.seeds.any { it.title == "Song 3" })
        assertTrue(ctx.seeds.zipWithNext().all { (a, b) -> a.weight >= b.weight })
    }

    @Test fun `classify separates skips, plays and completions`() {
        assertEquals(ListenOutcome.EARLY_SKIP, AutoplayEngine.classify(10_000, 200_000))
        assertEquals(ListenOutcome.PLAYED, AutoplayEngine.classify(60_000, 200_000))
        assertEquals(ListenOutcome.COMPLETED, AutoplayEngine.classify(170_000, 200_000))
        // A 40 s interlude heard for 25 s isn't a skip.
        assertEquals(ListenOutcome.PLAYED, AutoplayEngine.classify(25_000, 40_000))
        assertEquals(ListenOutcome.COMPLETED, AutoplayEngine.classify(160_000, 0))
    }
}
