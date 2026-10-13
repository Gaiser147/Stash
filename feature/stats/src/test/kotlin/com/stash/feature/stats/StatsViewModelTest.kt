package com.stash.feature.stats

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.stats.ListeningStats
import com.stash.core.data.stats.StatsAggregator
import com.stash.core.data.stats.StatsRange
import com.stash.core.data.stats.StatsRepository
import com.stash.core.data.stats.TasteProfile
import com.stash.core.media.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun stats(range: StatsRange) = ListeningStats(
        range = range, listenedMs = 3_600_000, plays = 12, distinctSongs = 9, distinctArtists = 4, skips = 3,
        discoveriesKept = 2, perDay = List(7) { 0L }, perHour = List(24) { 0L }, weekdayHour = List(7) { List(24) { 0L } },
        topTracks = emptyList(), topArtists = emptyList(), topTags = emptyList(),
        sound = StatsAggregator.sound(emptyList()),
    )

    private val repo = mock<StatsRepository> {
        onBlocking { stats(any(), any()) } doReturn stats(StatsRange.MONTH)
        onBlocking { taste(any()) } doReturn TasteProfile(emptyList(), emptyList(), 0.2f, listOf("rested artist"))
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `loads the month and the taste profile, switches range`() = runTest(dispatcher) {
        val vm = StatsViewModel(repo, mock<PlayerRepository>())
        advanceUntilIdle()
        assertThat(vm.state.value.stats?.plays).isEqualTo(12)
        assertThat(vm.state.value.stats?.skipRate).isWithin(1e-4f).of(0.2f)
        assertThat(vm.state.value.taste?.restingArtists).containsExactly("rested artist")

        vm.load(StatsRange.WEEK)
        advanceUntilIdle()
        assertThat(vm.state.value.range).isEqualTo(StatsRange.WEEK)
        verifyBlocking(repo) { stats(org.mockito.kotlin.eq(StatsRange.WEEK), any()) }
    }

    @Test fun `allow again clears the artist and reloads the profile`() = runTest(dispatcher) {
        val vm = StatsViewModel(repo, mock<PlayerRepository>())
        advanceUntilIdle()
        vm.allowAgain("rested artist")
        advanceUntilIdle()
        verifyBlocking(repo) { allowArtistAgain("rested artist") }
    }
}
