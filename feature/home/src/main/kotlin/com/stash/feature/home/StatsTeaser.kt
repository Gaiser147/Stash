package com.stash.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.stats.StatsRange
import com.stash.core.data.stats.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** This week's listening time and top artist for the Home teaser. */
data class StatsTeaserState(val listenedMs: Long, val topArtist: String?)

@HiltViewModel
class StatsTeaserViewModel @Inject constructor(
    private val repository: StatsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<StatsTeaserState?>(null)
    val state: StateFlow<StatsTeaserState?> = _state.asStateFlow()

    /** Called when Home is shown, so the numbers include what was just heard. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.stats(StatsRange.WEEK) }.getOrNull()?.let { s ->
                _state.value = StatsTeaserState(s.listenedMs, s.topArtists.firstOrNull()?.artist)
            }
        }
    }
}

/** Entry to the statistics page: "Your stats · 3 h 20 min this week · mostly Dua Lipa". */
@Composable
fun StatsTeaserCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsTeaserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_teaser_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val s = state
                val line = when {
                    s == null -> stringResource(R.string.stats_teaser_subtitle)
                    s.topArtist != null -> stringResource(R.string.stats_teaser_week_artist, minutesText(s.listenedMs), s.topArtist)
                    else -> stringResource(R.string.stats_teaser_week, minutesText(s.listenedMs))
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun minutesText(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) stringResource(R.string.stats_teaser_hours, (m / 60).toInt(), (m % 60).toInt())
    else stringResource(R.string.stats_teaser_minutes, m.toInt())
}
