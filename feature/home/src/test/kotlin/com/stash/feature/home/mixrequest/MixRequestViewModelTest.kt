package com.stash.feature.home.mixrequest

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.autoplay.MixRequest
import com.stash.core.data.prefs.MixRequestHistory
import com.stash.core.media.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class MixRequestViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val player = mock<PlayerRepository> {
        onBlocking { libraryArtists(any()) } doReturn listOf("Billie Eilish", "Dua Lipa")
        onBlocking { startPersonalMix(any<MixRequest>()) } doReturn true
    }
    private val history = mock<MixRequestHistory> { on { recent } doReturn flowOf(emptyList()) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `typing fills the controls and starting sends the wish`() = runTest(dispatcher) {
        val vm = MixRequestViewModel(player, history)
        advanceUntilIdle()

        vm.onTextChange("ruhig, langsam, wie billie eilish")
        val s = vm.state.value
        assertThat(s.moods).containsExactly("chill")
        assertThat(s.tempo).isEqualTo(TempoChoice.SLOW)
        assertThat(s.artists).containsExactly("Billie Eilish")

        vm.setFamiliarity(0.2f)
        vm.start()
        advanceUntilIdle()

        val captor = argumentCaptor<MixRequest>()
        verifyBlocking(player) { startPersonalMix(captor.capture()) }
        assertThat(captor.firstValue.moods).containsExactly("chill")
        assertThat(captor.firstValue.bpmMax).isEqualTo(105f)
        assertThat(captor.firstValue.familiarity).isEqualTo(0.2f)
        verifyBlocking(history) { remember(captor.firstValue) }
    }

    @Test fun `a failed mix is not remembered`() = runTest(dispatcher) {
        val failing = mock<PlayerRepository> {
            onBlocking { libraryArtists(any()) } doReturn emptyList()
            onBlocking { startPersonalMix(any<MixRequest>()) } doReturn false
        }
        val vm = MixRequestViewModel(failing, history)
        vm.toggleMood("party")
        vm.start()
        advanceUntilIdle()
        verifyBlocking(history, never()) { remember(any()) }
        assertThat(vm.state.value.building).isFalse()
    }
}
