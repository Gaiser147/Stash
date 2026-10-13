package com.stash.feature.home.mixrequest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.autoplay.MixRequest
import com.stash.core.data.autoplay.MixRequestParser
import com.stash.core.data.mix.GenreCatalog
import com.stash.core.data.mix.MoodTagMap
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.feature.home.R

/**
 * "Mix für mich" with a wish: free text plus mood chips, energy and tempo,
 * known ↔ new, genres, artists and length. The text is parsed live and the
 * controls follow it; what was understood is shown as chips.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MixRequestSheet(
    onDismiss: () -> Unit,
    onFailed: (String) -> Unit,
    viewModel: MixRequestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val failedText = stringResource(R.string.mix_request_failed)

    LaunchedEffect(viewModel) {
        viewModel.started.collect { ok -> if (ok) onDismiss() else onFailed(failedText) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.mix_request_title),
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::reset) { Text(stringResource(R.string.mix_request_reset)) }
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                placeholder = { Text(stringResource(R.string.mix_request_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.start() }),
            )
            if (state.understood.isNotEmpty()) {
                Label(stringResource(R.string.mix_request_understood))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.understood.forEach { t -> SuggestionChip(onClick = {}, label = { Text(tokenLabel(t)) }) }
                }
            }

            if (recent.isNotEmpty() && state.text.isBlank()) {
                Label(stringResource(R.string.mix_request_recent))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { r ->
                        AssistChip(
                            onClick = { viewModel.apply(r) },
                            label = { Text(summary(r)) },
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }

            Label(stringResource(R.string.mix_request_mood))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MoodTagMap.ALL_MOODS.forEach { m ->
                    FilterChip(selected = m in state.moods, onClick = { viewModel.toggleMood(m) }, label = { Text(moodLabel(m)) })
                }
            }

            Label(stringResource(R.string.mix_request_energy))
            val energy = state.energy
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = energy == null,
                    onClick = { viewModel.setEnergy(if (energy == null) 0.2f..0.8f else null) },
                    label = { Text(stringResource(R.string.mix_request_energy_any)) },
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.mix_request_energy_calm), style = MaterialTheme.typography.labelSmall)
                RangeSlider(
                    value = energy ?: 0f..1f,
                    onValueChange = { viewModel.setEnergy(it) },
                    enabled = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(stringResource(R.string.mix_request_energy_loud), style = MaterialTheme.typography.labelSmall)
            }

            Label(stringResource(R.string.mix_request_tempo))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val choices = TempoChoice.entries
                choices.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = state.tempo == t,
                        onClick = { viewModel.setTempo(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    ) { Text(tempoLabel(t)) }
                }
            }

            Label(stringResource(R.string.mix_request_familiarity))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mix_request_known), style = MaterialTheme.typography.labelSmall)
                // Slider runs known (left) → new (right); familiarity is the reverse.
                Slider(
                    value = 1f - state.familiarity,
                    onValueChange = { viewModel.setFamiliarity(1f - it) },
                    valueRange = 0f..(MixRequest.MAX_DISCOVERY_SHARE),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(stringResource(R.string.mix_request_new), style = MaterialTheme.typography.labelSmall)
            }

            Label(stringResource(R.string.mix_request_artists))
            if (state.artists.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.artists.forEach { a ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.removeArtist(a) },
                            label = { Text(a) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.artistQuery,
                onValueChange = viewModel::onArtistQuery,
                placeholder = { Text(stringResource(R.string.mix_request_artist_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.artistSuggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.artistSuggestions.forEach { a -> SuggestionChip(onClick = { viewModel.addArtist(a) }, label = { Text(a) }) }
                }
            }

            Label(stringResource(R.string.mix_request_genres))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GenreCatalog.FAMILIES.map { it.genres.first() }.plus(state.genres).distinct().forEach { g ->
                    FilterChip(selected = g in state.genres, onClick = { viewModel.toggleGenre(g) }, label = { Text(g) })
                }
            }

            Label(stringResource(R.string.mix_request_length))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val sizes = listOf(20, 30, 50)
                sizes.forEachIndexed { i, n ->
                    SegmentedButton(
                        selected = state.size == n,
                        onClick = { viewModel.setSize(n) },
                        shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                    ) { Text(stringResource(R.string.mix_request_songs, n)) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::start,
                enabled = !state.building,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.building) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.mix_request_start), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun moodLabel(mood: String): String = when (mood) {
    "chill" -> stringResource(R.string.mood_chill)
    "energetic" -> stringResource(R.string.mood_energetic)
    "focus" -> stringResource(R.string.mood_focus)
    "party" -> stringResource(R.string.mood_party)
    "melancholy" -> stringResource(R.string.mood_melancholy)
    "romantic" -> stringResource(R.string.mood_romantic)
    else -> mood
}

@Composable
private fun tempoLabel(t: TempoChoice): String = when (t) {
    TempoChoice.ANY -> stringResource(R.string.mix_request_tempo_any)
    TempoChoice.SLOW -> stringResource(R.string.mix_request_tempo_slow)
    TempoChoice.MEDIUM -> stringResource(R.string.mix_request_tempo_medium)
    TempoChoice.FAST -> stringResource(R.string.mix_request_tempo_fast)
}

@Composable
private fun tokenLabel(t: MixRequestParser.Token): String = when (t.kind) {
    MixRequestParser.Kind.MOOD -> moodLabel(t.value)
    MixRequestParser.Kind.TEMPO -> when (t.value) {
        "slow" -> stringResource(R.string.mix_request_tempo_slow)
        "fast" -> stringResource(R.string.mix_request_tempo_fast)
        else -> "${stringResource(R.string.mix_request_tempo)}: ${t.value}"
    }
    MixRequestParser.Kind.ENERGY -> when (t.value) {
        "loud" -> stringResource(R.string.mix_request_energy_loud)
        else -> "${stringResource(R.string.mix_request_energy)} ≤ 60 %"
    }
    MixRequestParser.Kind.FAMILIARITY ->
        if (t.value == "new") stringResource(R.string.mix_request_new) else stringResource(R.string.mix_request_known)
    MixRequestParser.Kind.GENRE, MixRequestParser.Kind.ARTIST -> t.value
}

/** Short label for a stored wish ("Ruhig · Langsam · Billie Eilish"). */
@Composable
private fun summary(r: MixRequest): String {
    val parts = ArrayList<String>()
    r.moods.forEach { parts += moodLabel(it) }
    when (TempoChoice.of(r.bpmMin, r.bpmMax)) {
        TempoChoice.ANY -> Unit
        else -> parts += tempoLabel(TempoChoice.of(r.bpmMin, r.bpmMax))
    }
    parts += r.genres
    parts += r.artists
    r.familiarity?.let { parts += if (it < 0.5f) stringResource(R.string.mix_request_new) else stringResource(R.string.mix_request_known) }
    return parts.take(4).joinToString(" · ").ifEmpty { stringResource(R.string.mix_request_title) }
}
