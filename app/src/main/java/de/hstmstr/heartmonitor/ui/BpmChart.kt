package de.hstmstr.heartmonitor.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hstmstr.heartmonitor.data.BpmTrackPoint
import de.hstmstr.heartmonitor.ui.theme.HeartMonitorTheme
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A minimal bpm-over-time line chart drawn straight onto a [Canvas] – no chart
 * library. Stateless: hand it the points from
 * [de.hstmstr.heartmonitor.data.HeartRateCsvSummary.parseSeries].
 */
@Composable
fun BpmChart(
    points: List<BpmTrackPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Zu wenige Datenpunkte für ein Diagramm",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.15f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val density = LocalDensity.current

    val labelPaint = remember(labelArgb, density) {
        Paint().apply {
            isAntiAlias = true
            color = labelArgb
            textSize = with(density) { 11.sp.toPx() }
        }
    }

    val minBpm = points.minOf { it.bpm }
    val maxBpm = points.maxOf { it.bpm }
    // Round the axis outwards to the next 10 with a little headroom.
    val yLow = (floor((minBpm - 2) / 10.0) * 10).toInt()
    val yHigh = (ceil((maxBpm + 2) / 10.0) * 10).toInt()
    val yStep = if (yHigh - yLow > 60) 20 else 10

    val xMin = points.first().elapsedSeconds
    val xSpan = (points.last().elapsedSeconds - xMin).takeIf { it > 0.0 } ?: 1.0

    Canvas(modifier) {
        val leftPad = with(density) { 40.dp.toPx() }
        val bottomPad = with(density) { 20.dp.toPx() }
        val topPad = with(density) { 8.dp.toPx() }
        val rightPad = with(density) { 8.dp.toPx() }
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - bottomPad
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        fun xPx(t: Double): Float = leftPad + ((t - xMin) / xSpan * plotW).toFloat()
        fun yPx(b: Int): Float =
            topPad + ((yHigh - b).toDouble() / (yHigh - yLow) * plotH).toFloat()

        // Horizontal gridlines + bpm labels.
        labelPaint.textAlign = Paint.Align.RIGHT
        var g = yLow
        while (g <= yHigh) {
            val y = yPx(g)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(leftPad + plotW, y),
                strokeWidth = 1f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                g.toString(),
                leftPad - with(density) { 6.dp.toPx() },
                y + with(density) { 4.dp.toPx() },
                labelPaint,
            )
            g += yStep
        }

        // Time labels at start / middle / end (m:ss relative to the first sample).
        val ticks = listOf(
            0f to Paint.Align.LEFT,
            0.5f to Paint.Align.CENTER,
            1f to Paint.Align.RIGHT,
        )
        ticks.forEach { (frac, align) ->
            labelPaint.textAlign = align
            drawContext.canvas.nativeCanvas.drawText(
                formatElapsed(xSpan * frac),
                leftPad + frac * plotW,
                size.height - with(density) { 4.dp.toPx() },
                labelPaint,
            )
        }

        // The bpm line, plus a soft fill down to the baseline.
        val line = Path().apply {
            moveTo(xPx(points.first().elapsedSeconds), yPx(points.first().bpm))
            for (i in 1 until points.size) {
                lineTo(xPx(points[i].elapsedSeconds), yPx(points[i].bpm))
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xPx(points.last().elapsedSeconds), topPad + plotH)
            lineTo(xPx(points.first().elapsedSeconds), topPad + plotH)
            close()
        }
        drawPath(area, fillColor)
        drawPath(
            line,
            color = lineColor,
            style = Stroke(width = with(density) { 2.dp.toPx() }),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BpmChartPreview() {
    val demo = buildList {
        var bpm = 78.0
        repeat(120) { i ->
            bpm += (if (i % 7 < 4) 3 else -4) + (i % 3 - 1)
            add(BpmTrackPoint(elapsedSeconds = i * 3.0, bpm = bpm.coerceIn(60.0, 180.0).toInt()))
        }
    }
    HeartMonitorTheme {
        BpmChart(
            points = demo,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }
}
