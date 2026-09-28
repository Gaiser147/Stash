package com.stash.core.data.autoplay

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room queries backing autoplay's library candidates. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutoplayQueriesTest {

    private lateinit var db: StashDatabase

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        (1L..6L).forEach { id ->
            db.trackDao().insert(
                TrackEntity(
                    id = id, title = "t$id", artist = "a$id",
                    isDownloaded = id <= 3, isStreamable = id == 4L,
                ),
            )
        }
    }

    @After fun tearDown() { db.close() }

    private suspend fun listen(trackId: Long, at: Long) =
        db.listeningEventDao().insert(ListeningEventEntity(trackId = trackId, startedAt = at))

    @Test fun `transitions count what follows the seed inside the window`() = runTest {
        val min = 60_000L
        // Session 1: 1 → 2 → 3 ; Session 2: 1 → 2 ; far later: 5 (outside window).
        listen(1, 1_000 * min); listen(2, 1_004 * min); listen(3, 1_008 * min)
        listen(1, 2_000 * min); listen(2, 2_003 * min)
        listen(5, 2_100 * min)

        val rows = db.listeningEventDao().getTransitionsFrom(listOf(1L), sinceMs = 0, windowMs = 15 * min)
            .associate { it.trackId to it.plays }

        assertEquals(2, rows[2L])
        assertEquals(1, rows[3L])
        assertTrue(5L !in rows)
        assertTrue("the seed itself is never its own transition", 1L !in rows)
    }

    @Test fun `co-playlist ignores liked songs and stash mixes`() = runTest {
        val custom = db.playlistDao().insert(PlaylistEntity(name = "Road trip", source = MusicSource.SPOTIFY, sourceId = "p1"))
        val liked = db.playlistDao().insert(
            PlaylistEntity(name = "Liked", source = MusicSource.SPOTIFY, sourceId = "p2", type = PlaylistType.LIKED_SONGS),
        )
        val mix = db.playlistDao().insert(
            PlaylistEntity(name = "Mix", source = MusicSource.SPOTIFY, sourceId = "p3", type = PlaylistType.STASH_MIX),
        )
        listOf(1L, 2L).forEach { db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(custom, it)) }
        listOf(1L, 3L).forEach { db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(liked, it)) }
        listOf(1L, 4L).forEach { db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(mix, it)) }

        val rows = db.playlistDao().getCoPlaylistTracks(listOf(1L)).associate { it.trackId to it.shared }

        assertEquals(mapOf(2L to 1), rows)
    }

    @Test fun `playable pool honours the streaming flag`() = runTest {
        assertEquals(setOf(1L, 2L, 3L), db.trackDao().getAllPlayable(includeStreamable = false).map { it.id }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 4L), db.trackDao().getAllPlayable(includeStreamable = true).map { it.id }.toSet())
    }
}
