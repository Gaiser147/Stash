package com.stash.feature.home.mixrequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.autoplay.MixRequest
import com.stash.core.data.autoplay.MixRequestParser
import com.stash.core.data.prefs.MixRequestHistory
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tempo choice in the wish sheet. */
enum class TempoChoice(val bpmMin: Float?, val bpmMax: Float?) {
    ANY(null, null), SLOW(null, 105f), MEDIUM(90f, 130f), FAST(118f, null);

    companion object {
        fun of(min: Float?, max: Float?): TempoChoice = when {
            min == null && max == null -> ANY
            min == null -> SLOW
            max == null -> FAST
            else -> MEDIUM
        }
    }
}

/**
 * Everything the wish sheet shows. The text field and the controls are two
 * views of one wish: typing re-parses the text into the controls it mentions;
 * touching a control changes only that control.
 */
data class MixRequestUiState(
    val text: String = "",
    val moods: Set<String> = emptySet(),
    val energy: ClosedFloatingPointRange<Float>? = null,
    val tempo: TempoChoice = TempoChoice.ANY,
    val familiarity: Float = DEFAULT_FAMILIARITY,
    val familiaritySet: Boolean = false,
    val genres: Set<String> = emptySet(),
    val artists: List<String> = emptyList(),
    val size: Int = MixRequest.DEFAULT_SIZE,
    val understood: List<MixRequestParser.Token> = emptyList(),
    val artistQuery: String = "",
    val artistSuggestions: List<String> = emptyList(),
    val building: Boolean = false,
) {
    fun toRequest() = MixRequest(
        energy = energy,
        bpmMin = tempo.bpmMin,
        bpmMax = tempo.bpmMax,
        moods = moods,
        genres = genres,
        artists = artists,
        familiarity = familiarity.takeIf { familiaritySet },
        size = size,
    )

    companion object {
        const val DEFAULT_FAMILIARITY = 0.6f
    }
}

@HiltViewModel
class MixRequestViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val history: MixRequestHistory,
) : ViewModel() {

    private val _state = MutableStateFlow(MixRequestUiState())
    val state: StateFlow<MixRequestUiState> = _state.asStateFlow()

    val recent: StateFlow<List<MixRequest>> =
        history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Emits once the mix is playing (true) or could not be built (false). */
    private val _started = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val started: SharedFlow<Boolean> = _started.asSharedFlow()

    private var artists: List<String> = emptyList()

    init {
        viewModelScope.launch { artists = playerRepository.libraryArtists() }
    }

    fun onTextChange(text: String) {
        val parsed = MixRequestParser.parse(text, artists)
        val r = parsed.request
        _state.update { s ->
            s.copy(
                text = text,
                understood = parsed.tokens,
                moods = if (r.moods.isNotEmpty()) r.moods else s.moods,
                energy = r.energy ?: s.energy,
                tempo = if (r.bpmMin != null || r.bpmMax != null) TempoChoice.of(r.bpmMin, r.bpmMax) else s.tempo,
                familiarity = r.familiarity ?: s.familiarity,
                familiaritySet = s.familiaritySet || r.familiarity != null,
                genres = s.genres + r.genres,
                artists = (s.artists + r.artists).distinctBy { it.lowercase() },
            )
        }
    }

    fun toggleMood(mood: String) = _state.update { s ->
        val moods = if (mood in s.moods) s.moods - mood else s.moods + mood
        s.copy(moods = moods)
    }

    fun setEnergy(range: ClosedFloatingPointRange<Float>?) = _state.update { it.copy(energy = range) }
    fun setTempo(t: TempoChoice) = _state.update { it.copy(tempo = t) }
    fun setFamiliarity(v: Float) = _state.update { it.copy(familiarity = v, familiaritySet = true) }
    fun toggleGenre(g: String) = _state.update { s -> s.copy(genres = if (g in s.genres) s.genres - g else s.genres + g) }
    fun setSize(n: Int) = _state.update { it.copy(size = n) }
    fun removeArtist(a: String) = _state.update { s -> s.copy(artists = s.artists.filterNot { it.equals(a, ignoreCase = true) }) }

    fun onArtistQuery(q: String) = _state.update { s ->
        val needle = q.trim().lowercase()
        s.copy(
            artistQuery = q,
            artistSuggestions = if (needle.length < 2) emptyList()
                else artists.filter { it.lowercase().contains(needle) && s.artists.none { a -> a.equals(it, true) } }.take(6),
        )
    }

    fun addArtist(a: String) = _state.update { s ->
        if (s.artists.any { it.equals(a, true) }) s.copy(artistQuery = "", artistSuggestions = emptyList())
        else s.copy(artists = s.artists + a, artistQuery = "", artistSuggestions = emptyList())
    }

    /** Fills the sheet from a recent wish. */
    fun apply(r: MixRequest) = _state.value.let {
        _state.value = MixRequestUiState(
            moods = r.moods,
            energy = r.energy,
            tempo = TempoChoice.of(r.bpmMin, r.bpmMax),
            familiarity = r.familiarity ?: MixRequestUiState.DEFAULT_FAMILIARITY,
            familiaritySet = r.familiarity != null,
            genres = r.genres,
            artists = r.artists,
            size = r.size,
        )
    }

    fun reset() { _state.value = MixRequestUiState() }

    fun start() {
        val s = _state.value
        if (s.building) return
        _state.update { it.copy(building = true) }
        viewModelScope.launch {
            val request = s.toRequest()
            val ok = try {
                playerRepository.startPersonalMix(request)
            } finally {
                _state.update { it.copy(building = false) }
            }
            if (ok) runCatching { history.remember(request) }
            _started.tryEmit(ok)
        }
    }
}
