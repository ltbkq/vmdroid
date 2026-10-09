package io.github.ltbkq.vmdroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens

@Composable
fun VmLoadGraph(
    samples: List<Float>,
    currentPercent: Float?,
    modifier: Modifier = Modifier,
    unavailableReason: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val headline = when {
            unavailableReason != null -> unavailableReason
            currentPercent != null -> stringResource(R.string.status_vm_load_current, currentPercent.toInt())
            samples.isNotEmpty() -> stringResource(R.string.status_vm_load_collecting)
            else -> stringResource(R.string.status_vm_load_waiting)
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = VmdroidTokens.Spacing.SM),
        )

        if (unavailableReason != null) {
            Text(
                text = stringResource(R.string.status_vm_load_graph_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        val lineColor = MaterialTheme.colorScheme.primary
        val fillColor = lineColor.copy(alpha = 0.18f)
        val gridColor = MaterialTheme.colorScheme.outlineVariant

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val w = size.width
            val h = size.height
            val pad = 4f

            for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                val y = h - pad - (h - 2 * pad) * fraction
                drawLine(
                    color = gridColor,
                    start = Offset(pad, y),
                    end = Offset(w - pad, y),
                    strokeWidth = 1f,
                )
            }

            if (samples.size < 2) return@Canvas

            val stepX = (w - 2 * pad) / (samples.size - 1).coerceAtLeast(1)
            val points = samples.mapIndexed { i, pct ->
                val x = pad + i * stepX
                val y = h - pad - (pct.coerceIn(0f, 100f) / 100f) * (h - 2 * pad)
                Offset(x, y)
            }

            val fillPath = Path().apply {
                moveTo(points.first().x, h - pad)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, h - pad)
                close()
            }
            drawPath(fillPath, fillColor)

            for (i in 0 until points.lastIndex) {
                drawLine(
                    color = lineColor,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Text(
            text = stringResource(R.string.status_vm_load_axis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = VmdroidTokens.Spacing.XS),
        )
    }
}
