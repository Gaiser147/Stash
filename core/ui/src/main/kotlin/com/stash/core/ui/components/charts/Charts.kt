package com.stash.core.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small, dependency-free charts for the statistics page, drawn with Compose
 * Canvas in the theme's colours (they follow light/dark automatically).
 */

/**
 * Vertical bars, one per value, with optional labels under some of them.
 * [highlight] marks one bar (e.g. today) in the accent colour; the others
 * use a muted variant.
 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    labels: List<String?> = emptyList(),
    highlight: Int? = null,
    description: String = "",
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = accent.copy(alpha = 0.45f)
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Column(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (values.isEmpty()) return@Canvas
            val gap = (size.width / values.size) * 0.25f
            val w = (size.width - gap * (values.size - 1)) / values.size
            val r = CornerRadius(minOf(w / 2f, 6.dp.toPx()))
            values.forEachIndexed { i, v ->
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), r)
                val h = (v / max).coerceIn(0f, 1f) * size.height
                if (h > 0f) {
                    drawRoundRect(
                        if (i == highlight) accent else muted,
                        Offset(x, size.height - h),
                        Size(w, h),
                        r,
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                labels.forEach { l ->
                    Text(
                        text = l.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        softWrap = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Grid of cells shaded by value (e.g. weekday × hour). Rows are labelled on the left. */
@Composable
fun Heatmap(
    rows: List<List<Float>>,
    rowLabels: List<String>,
    modifier: Modifier = Modifier,
    columnLabels: List<String?> = emptyList(),
    description: String = "",
) {
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val max = rows.flatten().maxOrNull()?.takeIf { it > 0f } ?: 1f
    Column(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { r, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rowLabels.getOrElse(r) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val gap = 2.dp.toPx()
                    val w = (size.width - gap * (row.size - 1)) / row.size
                    row.forEachIndexed { c, v ->
                        val t = (v / max).coerceIn(0f, 1f)
                        val color = if (t <= 0f) empty else lerp(accent.copy(alpha = 0.15f), accent, t)
                        drawRoundRect(color, Offset(c * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
                    }
                }
            }
        }
        if (columnLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
                columnLabels.forEach { l ->
                    Text(
                        l.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One labelled horizontal share bar ("rock ███████ 34 %"). */
@Composable
fun ShareBar(
    label: String,
    share: Float,
    modifier: Modifier = Modifier,
    valueText: String = "${(share * 100).toInt()} %",
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                drawRect(color.copy(alpha = 0.15f))
                drawRect(color, size = Size(size.width * share.coerceIn(0f, 1f), size.height))
            }
        }
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}
