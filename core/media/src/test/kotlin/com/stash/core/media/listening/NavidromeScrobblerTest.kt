package com.stash.core.media.listening

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.PlayerRepository
import com.stash.data.download.navidrome.NavidromeServerConfig
import com.stash.data.download.navidrome.NavidromeServerPreferences
import com.stash.data.download.navidrome.SubsonicClient
import com.stash.data.download.navidrome.SubsonicException
import com.stash.data.download.navidrome.SubsonicSong
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NavidromeScrobblerTest {

    private val now = 1_790_000_000_000L
    private val dao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk()
    private val prefs: NavidromeServerPreferences = mockk()
    private val client: SubsonicClient = mockk()
    private val player: PlayerRepository = mockk(relaxed = true)

    private val scrobbler = NavidromeScrobbler(player, dao, trackDao, prefs, client, TestScope(), { now })

    private val config = NavidromeServerConfig("https://nd.example", "stash", "pw", scrobbleEnabled = true)

    private fun event(id: Long, trackId: Long, ageMs: Long = 60_000) =
        ListeningEventEntity(id = id, trackId = trackId, startedAt = now - ageMs)

    private fun track(id: Long) = TrackEntity(id = id, title = "Song $id", artist = "Artist", isrc = "ISRC$id", durationMs = 200_000)

    private fun onServer(trackId: Long, songId: String?) {
        coEvery { client.findSong(config, "Artist", "Song $trackId", "ISRC$trackId", 200_000) } returns
            songId?.let { SubsonicSong(it, "Artist", "Song $trackId", 200, listOf("ISRC$trackId")) }
    }

    @Test fun `plays on the server are scrobbled with their start time and marked`() = runTest {
        coEvery { dao.pendingNavidromeScrobbles(any()) } returns listOf(event(1, 10), event(2, 11))
        coEvery { trackDao.getById(10) } returns track(10)
        coEvery { trackDao.getById(11) } returns track(11)
        onServer(10, "s10")
        onServer(11, "s11")
        coEvery { client.scrobble(any(), any(), any(), any()) } returns Unit

        scrobbler.drain(config)

        coVerify { client.scrobble(config, "s10", now - 60_000, submission = true) }
        coVerify { client.scrobble(config, "s11", now - 60_000, submission = true) }
        coVerify { dao.markNavidromeScrobbled(1) }
        coVerify { dao.markNavidromeScrobbled(2) }
    }

    @Test fun `a song not on the server waits, then is dropped after the retry window`() = runTest {
        coEvery { dao.pendingNavidromeScrobbles(any()) } returns listOf(
            event(1, 10, ageMs = 60_000),
            event(2, 11, ageMs = NavidromeScrobbler.GIVE_UP_AFTER_MS + 1),
        )
        coEvery { trackDao.getById(10) } returns track(10)
        coEvery { trackDao.getById(11) } returns track(11)
        onServer(10, null)
        onServer(11, null)

        scrobbler.drain(config)

        coVerify(exactly = 0) { dao.markNavidromeScrobbled(1) }
        coVerify { dao.markNavidromeScrobbled(2) }
        coVerify(exactly = 0) { client.scrobble(any(), any(), any(), any()) }
    }

    @Test fun `an unreachable server stops the drain and keeps everything pending`() = runTest {
        coEvery { dao.pendingNavidromeScrobbles(any()) } returns listOf(event(1, 10), event(2, 11))
        coEvery { trackDao.getById(any()) } answers { track(firstArg()) }
        coEvery { client.findSong(any(), any(), any(), any(), any()) } throws IOException("timeout")

        scrobbler.drain(config)

        coVerify(exactly = 1) { client.findSong(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.markNavidromeScrobbled(any()) }
    }

    @Test fun `wrong credentials pause reporting without dropping plays`() = runTest {
        coEvery { dao.pendingNavidromeScrobbles(any()) } returns listOf(event(1, 10))
        coEvery { trackDao.getById(10) } returns track(10)
        coEvery { client.findSong(any(), any(), any(), any(), any()) } throws SubsonicException(40, "Wrong username or password")

        scrobbler.drain(config)

        coVerify(exactly = 0) { dao.markNavidromeScrobbled(any()) }
    }

    @Test fun `switched off or not configured marks the backlog handled instead of replaying it later`() = runTest {
        scrobbler.drain(config.copy(scrobbleEnabled = false))
        scrobbler.drain(config.copy(password = ""))

        coVerify(exactly = 2) { dao.markAllNavidromeScrobbled() }
        coVerify(exactly = 0) { dao.pendingNavidromeScrobbles(any()) }
    }

    @Test fun `a deleted track is skipped`() = runTest {
        coEvery { dao.pendingNavidromeScrobbles(any()) } returns listOf(event(1, 99))
        coEvery { trackDao.getById(99) } returns null

        scrobbler.drain(config)

        coVerify { dao.markNavidromeScrobbled(1) }
    }
}
