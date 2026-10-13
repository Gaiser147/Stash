package com.stash.core.data.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DiscoveryFeedbackEntity
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import com.stash.core.data.db.entity.TrackTagEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room queries behind the statistics page and the v35 listening depth. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StatsQueriesTest {

    private lateinit var db: StashDatabase

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.trackDao().insert(TrackEntity(id = 1, title = "One", artist = "Alpha", durationMs = 200_000))
        db.trackDao().insert(TrackEntity(id = 2, title = "Two", artist = "alpha", durationMs = 180_000))
        db.trackDao().insert(TrackEntity(id = 3, title = "Three", artist = "Beta", durationMs = 300_000))
    }

    @After fun tearDown() { db.close() }

    @Test fun `plays, artists, skips, tags and kept discoveries`() = runTest {
        val le = db.listeningEventDao()
        val id1 = le.insert(ListeningEventEntity(trackId = 1, startedAt = 1_000, completedAt = 1_000, durationMs = 200_000))
        le.updateListened(id1, 190_000)
        le.updateListened(id1, 50_000) // never shrinks
        le.insert(ListeningEventEntity(trackId = 2, startedAt = 2_000, completedAt = 2_000))
        le.insert(ListeningEventEntity(trackId = 3, startedAt = 10, completedAt = 10))
        db.trackSkipEventDao().insert(TrackSkipEventEntity(trackId = 3, skippedAt = 3_000, positionMs = 12_000))
        db.trackTagDao().upsert(TrackTagEntity(trackId = 1, tag = "pop", weight = 1f, source = "TRACK", fetchedAt = 0))
        db.trackTagDao().upsert(TrackTagEntity(trackId = 2, tag = "pop", weight = 0.5f, source = "TRACK", fetchedAt = 0))
        db.trackTagDao().upsert(TrackTagEntity(trackId = 2, tag = "dance", weight = 0.4f, source = "TRACK", fetchedAt = 0))
        db.discoveryFeedbackDao().insert(DiscoveryFeedbackEntity(canonicalKey = "x|y", artistKey = "x", outcome = "COMPLETED", at = 1_500))
        db.discoveryFeedbackDao().insert(DiscoveryFeedbackEntity(canonicalKey = "x|z", artistKey = "x", outcome = "EARLY_SKIP", at = 1_600))

        val stats = db.statsDao()
        val plays = stats.playsSince(500)
        assertEquals(2, plays.size)
        assertEquals(190_000L, plays.first { it.trackId == 1L }.listenedMs)
        assertEquals(1, stats.distinctArtistsSince(500)) // "Alpha" and "alpha"
        assertEquals(1, stats.skipsSince(0))
        assertEquals(listOf("pop", "dance"), stats.topTagsSince(500).map { it.tag })
        assertEquals(1, stats.discoveriesKeptSince(0))

        val completion = le.getCompletionStatsSince(listOf(1L, 2L), 0).associateBy { it.trackId }
        assertEquals(1, completion.getValue(1L).completed)
    }

    @Test fun `a play heard only halfway no longer counts as completed`() = runTest {
        val le = db.listeningEventDao()
        val id = le.insert(ListeningEventEntity(trackId = 3, startedAt = 1_000, completedAt = 1_000, durationMs = 300_000))
        le.updateListened(id, 150_000)
        assertEquals(0, le.getCompletionStatsSince(listOf(3L), 0).single().completed)
    }
}
