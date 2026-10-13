package com.stash.feature.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.data.stats.ListeningStats
import com.stash.core.data.stats.StatsRange
import com.stash.core.data.stats.TasteProfile
import com.stash.core.data.stats.TrackStat
import com.stash.core.ui.components.charts.BarChart
import com.stash.core.ui.components.charts.Heatmap
import com.stash.core.ui.components.charts.ShareBar
import com.stash.core.ui.theme.SpaceGrotesk
import java.time.LocalDate

/**
 * Listening statistics and what the recommender has learned: listening time,
 * when you listen, top songs/artists/genres, the sound of what you hear
 * (server-side analysis) and the taste profile autoplay works with.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.stats_back))
                }
                Text(
                    stringResource(R.string.stats_title),
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val ranges = StatsRange.entries
                ranges.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = state.range == r,
                        onClick = { viewModel.load(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                    ) { Text(rangeLabel(r)) }
                }
            }
        }
        val stats = state.stats
        when {
            state.loading && stats == null -> item {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            stats == null || stats.plays == 0 -> item {
                Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
            }
            else -> {
                item { Overview(stats) }
                item { TimeCharts(stats) }
                if (stats.topTracks.isNotEmpty()) item { TopSongs(stats.topTracks, viewModel::play) }
                if (stats.topArtists.isNotEmpty()) item {
                    Card(stringResource(R.string.stats_top_artists)) {
                        val max = stats.topArtists.maxOf { it.plays }.coerceAtLeast(1)
                        stats.topArtists.forEach { a ->
                            ShareBar(
                                label = a.artist,
                                share = a.plays.toFloat() / max,
                                valueText = a.plays.toString(),
                                modifier = Modifier.clickable { onOpenArtist(a.artist) }.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
                if (stats.topTags.isNotEmpty()) item {
                    Card(stringResource(R.string.stats_top_genres)) {
                        stats.topTags.forEach { ShareBar(it.tag, it.share, Modifier.padding(vertical = 3.dp)) }
                    }
                }
                item { Sound(stats) }
            }
        }
        state.taste?.let { taste -> item { Taste(taste, viewModel::allowAgain) } }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(stats: ListeningStats) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            val on = MaterialTheme.colorScheme.onPrimary
            Text(stringResource(R.string.stats_listening_time), style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
            Text(
                duration(stats.listenedMs),
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium,
                color = on,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Figure(stats.distinctSongs.toString(), stringResource(R.string.stats_songs))
                Figure(stats.distinctArtists.toString(), stringResource(R.string.stats_artists))
                Figure(stats.discoveriesKept.toString(), stringResource(R.string.stats_new_kept))
                Figure("${(stats.skipRate * 100).toInt()} %", stringResource(R.string.stats_skip_rate))
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    val on = MaterialTheme.colorScheme.onPrimary
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = on)
        Text(label, style = MaterialTheme.typography.labelSmall, color = on.copy(alpha = 0.8f))
    }
}

@Composable
private fun TimeCharts(stats: ListeningStats) {
    Card(stringResource(R.string.stats_per_day)) {
        val n = stats.perDay.size
        val today = LocalDate.now()
        BarChart(
            values = stats.perDay.map { it.toFloat() },
            highlight = n - 1,
            labels = List(n) { i ->
                val day = today.minusDays((n - 1 - i).toLong())
                if (n <= 7 || i % 5 == 4 || i == n - 1) day.dayOfMonth.toString() else null
            },
        )
    }
    Spacer(Modifier.height(14.dp))
    Card(stringResource(R.string.stats_time_of_day)) {
        BarChart(
            values = stats.perHour.map { it.toFloat() },
            height = 90.dp,
            highlight = stats.perHour.indices.maxByOrNull { stats.perHour[it] },
            labels = List(24) { h -> if (h % 6 == 0) "$h" else null },
        )
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.stats_week_heatmap), style = MaterialTheme.typography.labelLarge)
        val days = stringResource(R.string.stats_weekdays).split(",")
        Heatmap(
            rows = stats.weekdayHour.map { row -> row.map { it.toFloat() } },
            rowLabels = days,
            columnLabels = List(24) { h -> if (h % 6 == 0) "$h" else null },
        )
    }
}

@Composable
private fun TopSongs(tracks: List<TrackStat>, onPlay: (TrackStat) -> Unit) {
    Card(stringResource(R.string.stats_top_songs)) {
        tracks.forEachIndexed { i, t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPlay(t) }.padding(vertical = 4.dp),
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
                AsyncImage(
                    model = t.track.albumArtUrl ?: t.track.albumArtPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(t.track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        t.track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.stats_plays, t.plays),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Sound(stats: ListeningStats) {
    val s = stats.sound
    Card(stringResource(R.string.stats_sound)) {
        if (s.plays == 0) {
            Text(stringResource(R.string.stats_sound_empty), style = MaterialTheme.typography.bodySmall)
            return@Card
        }
        Text(stringResource(R.string.stats_tempo), style = MaterialTheme.typography.labelLarge)
        BarChart(
            values = s.tempo,
            height = 70.dp,
            labels = listOf("<90", "90–110", "110–130", "130–150", "150+"),
            highlight = s.tempo.indices.maxByOrNull { s.tempo[it] },
        )
        s.medianBpm?.let { Text(stringResource(R.string.stats_median_bpm, it.toInt()), style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.stats_energy), style = MaterialTheme.typography.labelLarge)
        BarChart(
            values = s.energy,
            height = 70.dp,
            labels = listOf(stringResource(R.string.stats_calm), null, null, null, stringResource(R.string.stats_intense)),
            highlight = s.energy.indices.maxByOrNull { s.energy[it] },
        )
        Text(stringResource(R.string.stats_minor, (s.minorShare * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        if (s.energyByTimeOfDay.any { it != null }) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.stats_energy_by_time), style = MaterialTheme.typography.labelLarge)
            BarChart(
                values = s.energyByTimeOfDay.map { it ?: 0f },
                height = 70.dp,
                labels = s.energyByTimeOfDay.indices.map { "${it * 3}" },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Taste(taste: TasteProfile, onAllow: (String) -> Unit) {
    Card(stringResource(R.string.stats_taste)) {
        if (taste.topTags.isNotEmpty()) {
            Text(stringResource(R.string.stats_taste_tags), style = MaterialTheme.typography.labelLarge)
            val max = taste.topTags.maxOf { it.share }.coerceAtLeast(1e-6f)
            taste.topTags.forEach { ShareBar(it.tag, it.share / max, Modifier.padding(vertical = 2.dp), valueText = "") }
        }
        if (taste.favouriteArtists.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stats_taste_artists), style = MaterialTheme.typography.labelLarge)
            Text(
                taste.favouriteArtists.joinToString(" · ") { it.artist },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.stats_taste_discovery, (taste.discoveryShare * 100).toInt()),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (taste.restingArtists.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stats_taste_resting), style = MaterialTheme.typography.labelLarge)
            taste.restingArtists.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAllow(a) }) { Text(stringResource(R.string.stats_allow_again)) }
                }
            }
        }
    }
}

@Composable
private fun rangeLabel(r: StatsRange): String = when (r) {
    StatsRange.WEEK -> stringResource(R.string.stats_range_week)
    StatsRange.MONTH -> stringResource(R.string.stats_range_month)
    StatsRange.YEAR -> stringResource(R.string.stats_range_year)
    StatsRange.ALL -> stringResource(R.string.stats_range_all)
}

@Composable
internal fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) stringResource(R.string.stats_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    else stringResource(R.string.stats_minutes, minutes.toInt())
}
