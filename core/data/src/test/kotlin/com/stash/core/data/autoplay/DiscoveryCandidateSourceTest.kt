package com.stash.core.data.autoplay

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.ArtistDiscoveryStats
import com.stash.core.data.db.dao.DiscoveryFeedbackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarTrack
import com.stash.core.data.lastfm.LastFmTag
import com.stash.data.ytmusic.RadioTrack
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.MusicVideoType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCandidateSourceTest {

    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private val blocklist: BlocklistGuard = mockk()
    private val skips: TrackSkipEventDao = mockk()
    private val feedback: DiscoveryFeedbackDao = mockk()
    private val source = DiscoveryCandidateSource(lastFm, yt, blocklist, skips, feedback)

    private val context = AutoplayContext(
        seeds = listOf(AutoplayContext.Seed(1L, "Seed Artist", "Seed Song", 1f)),
        tagVector = emptyMap(),
        artistWeights = emptyMap(),
        recentArtists = emptyList(),
    )

    init {
        coEvery { blocklist.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { skips.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()
        coEvery { feedback.artistStatsSince(any()) } returns emptyList()
        coEvery { lastFm.getArtistTopTracks(any(), any()) } returns Result.success(emptyList())
        coEvery { lastFm.getTrackTopTags(any(), any()) } returns Result.success(emptyList())
        coEvery { lastFm.getArtistTopTags(any()) } returns Result.success(emptyList())
        coEvery { yt.getRadio(any(), any()) } returns emptyList()
        coEvery { lastFm.getSimilarTracks(any(), any(), any()) } returns Result.success(
            (1..6).map { LastFmSimilarTrack("Busy Artist", "Song $it", 0.9f, playcount = 50_000) } +
                (1..40).map { LastFmSimilarTrack("Artist $it", "Hit $it", 0.5f, playcount = 50_000) } +
                LastFmSimilarTrack("Tiny Artist", "Demo", 0.99f, playcount = 12),
        )
    }

    private suspend fun run(ctx: AutoplayContext = context, videoIds: List<String> = emptyList()) =
        source.candidates(ctx, emptySet(), emptySet(), emptyMap(), { a, t -> "${a.lowercase()}|${t.lowercase()}" }, videoIds)

    @Test fun `one artist contributes at most three songs and obscure ones are dropped`() = runTest {
        val out = run()
        assertEquals(DiscoveryCandidateSource.MAX_PER_ARTIST, out.count { it.artist == "Busy Artist" })
        assertFalse(out.any { it.artist == "Tiny Artist" })
    }

    @Test fun `artists the user keeps skipping across sessions are rested`() = runTest {
        coEvery { feedback.artistStatsSince(any()) } returns
            listOf(ArtistDiscoveryStats("busy artist", earlySkips = 2, completed = 0, total = 2, lastAt = 1L))
        assertFalse(run().any { it.artist == "Busy Artist" })
    }

    @Test fun `youtube radio songs join, podcasts don't`() = runTest {
        coEvery { yt.getRadio("vid1", any()) } returns listOf(
            RadioTrack("r1", "Radio Song", "Radio Artist", 200_000, MusicVideoType.ATV),
            RadioTrack("r2", "Episode 12", "Some Show", 2_000_000, MusicVideoType.PODCAST_EPISODE),
        )
        val out = run(videoIds = listOf("vid1"))
        assertTrue(out.any { it.title == "Radio Song" })
        assertFalse(out.any { it.title == "Episode 12" })
    }

    @Test fun `a discovery whose tags clash with the session mood is dropped`() = runTest {
        coEvery { lastFm.getTrackTopTags("Busy Artist", any()) } returns
            Result.success(listOf(LastFmTag("party", 100f), LastFmTag("club", 60f)))
        coEvery { lastFm.getTrackTopTags("Artist 1", any()) } returns Result.success(listOf(LastFmTag("chillout", 100f)))
        val calm = context.copy(moods = MoodSpace.moodsOf(mapOf("chill" to 1f)))
        val out = run(calm)
        assertFalse(out.any { it.artist == "Busy Artist" })
        assertTrue(out.first { it.artist == "Artist 1" }.tagFit > MoodSpace.NEUTRAL)
    }
}
