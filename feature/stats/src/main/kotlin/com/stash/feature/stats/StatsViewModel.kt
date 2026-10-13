package com.stash.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.stats.ListeningStats
import com.stash.core.data.stats.StatsRange
import com.stash.core.data.stats.StatsRepository
import com.stash.core.data.stats.TasteProfile
import com.stash.core.data.stats.TrackStat
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val range: StatsRange = StatsRange.MONTH,
    val loading: Boolean = true,
    val stats: ListeningStats? = null,
    val taste: TasteProfile? = null,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: StatsRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(StatsRange.MONTH)
        viewModelScope.launch {
            val taste = runCatching { repository.taste() }.getOrNull()
            _state.update { it.copy(taste = taste) }
        }
    }

    fun load(range: StatsRange) {
        loadJob?.cancel()
        _state.update { it.copy(range = range, loading = true) }
        loadJob = viewModelScope.launch {
            val stats = runCatching { repository.stats(range) }.getOrNull()
            _state.update { it.copy(stats = stats, loading = false) }
        }
    }

    /** Plays the top songs starting at [track]. */
    fun play(track: TrackStat) {
        val list = _state.value.stats?.topTracks ?: return
        val index = list.indexOf(track).coerceAtLeast(0)
        viewModelScope.launch { playerRepository.setQueue(list.map { it.track.toDomain() }, index) }
    }

    fun allowAgain(artistKey: String) {
        viewModelScope.launch {
            runCatching { repository.allowArtistAgain(artistKey) }
            val taste = runCatching { repository.taste() }.getOrNull()
            _state.update { it.copy(taste = taste) }
        }
    }
}
