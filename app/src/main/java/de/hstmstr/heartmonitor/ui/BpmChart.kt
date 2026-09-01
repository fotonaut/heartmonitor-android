package de.hstmstr.heartmonitor.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hstmstr.heartmonitor.data.BpmTrackPoint
import de.hstmstr.heartmonitor.recording.HeartRateZone
import de.hstmstr.heartmonitor.ui.theme.HeartMonitorTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A minimal bpm-over-time line chart drawn straight onto a [Canvas] – no chart
 * library. Hand it the points from
 * [de.hstmstr.heartmonitor.data.HeartRateCsvSummary.parseSeries].
 *
 * Features beyond a plain polyline:
 * - fixed [HeartRateZone] bands tint the background,
 * - the line breaks at [BpmTrackPoint.gapBefore] (a recording gap) instead of
 *   drawing a long diagonal,
 * - tapping or dragging across the chart pins a marker with the bpm and time of
 *   the nearest sample.
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
    val markerColor = MaterialTheme.colorScheme.onSurface
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipArgb = MaterialTheme.colorScheme.inverseOnSurface.toArgb()
    val ringColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current

    val labelPaint = remember(labelArgb, density) {
        Paint().apply {
            isAntiAlias = true
            color = labelArgb
            textSize = with(density) { 11.sp.toPx() }
        }
    }
    val tooltipPaint = remember(tooltipArgb, density) {
        Paint().apply {
            isAntiAlias = true
            color = tooltipArgb
            textSize = with(density) { 12.sp.toPx() }
            textAlign = Paint.Align.LEFT
        }
    }

    var selected by remember(points) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier
            .pointerInput(points) {
                detectTapGestures { pos ->
                    selected = nearestIndex(points, chartGeometry(size.width.toFloat(), size.height.toFloat(), this, points), pos.x)
                }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        selected = nearestIndex(points, chartGeometry(size.width.toFloat(), size.height.toFloat(), this, points), pos.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        selected = nearestIndex(points, chartGeometry(size.width.toFloat(), size.height.toFloat(), this, points), change.position.x)
                        change.consume()
                    },
                )
            },
    ) {
        val geo = chartGeometry(size.width, size.height, this, points)
        if (geo.plotW <= 0f || geo.plotH <= 0f) return@Canvas

        // Zone bands behind everything else.
        for (zone in HeartRateZone.entries) {
            val bandLow = maxOf(zone.lowerBpm, geo.yLow)
            val bandHigh = minOf(zone.upperBpm ?: geo.yHigh, geo.yHigh)
            if (bandHigh <= bandLow) continue
            val top = geo.yPx(bandHigh.toDouble())
            val bottom = geo.yPx(bandLow.toDouble())
            drawRect(
                color = zone.bandColor().copy(alpha = 0.13f),
                topLeft = Offset(geo.leftPad, top),
                size = Size(geo.plotW, bottom - top),
            )
        }

        // Horizontal gridlines + bpm labels.
        val yStep = if (geo.yHigh - geo.yLow > 60) 20 else 10
        labelPaint.textAlign = Paint.Align.RIGHT
        var g = geo.yLow
        while (g <= geo.yHigh) {
            val y = geo.yPx(g.toDouble())
            drawLine(
                color = gridColor,
                start = Offset(geo.leftPad, y),
                end = Offset(geo.plotRight, y),
                strokeWidth = 1f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                g.toString(),
                geo.leftPad - with(density) { 6.dp.toPx() },
                y + with(density) { 4.dp.toPx() },
                labelPaint,
            )
            g += yStep
        }

        // Time labels at start / middle / end (m:ss relative to the first sample).
        listOf(
            0f to Paint.Align.LEFT,
            0.5f to Paint.Align.CENTER,
            1f to Paint.Align.RIGHT,
        ).forEach { (frac, align) ->
            labelPaint.textAlign = align
            drawContext.canvas.nativeCanvas.drawText(
                formatElapsed(geo.xSpan * frac),
                geo.leftPad + frac * geo.plotW,
                size.height - with(density) { 4.dp.toPx() },
                labelPaint,
            )
        }

        // The bpm line, split into segments wherever a recording gap sits, each
        // with a soft fill down to the baseline.
        val strokePx = with(density) { 2.dp.toPx() }
        forEachSegment(points) { segment ->
            if (segment.size == 1) {
                val p = segment.first()
                drawCircle(lineColor, strokePx, Offset(geo.xPx(p.elapsedSeconds), geo.yPx(p.bpm.toDouble())))
                return@forEachSegment
            }
            val line = Path().apply {
                moveTo(geo.xPx(segment.first().elapsedSeconds), geo.yPx(segment.first().bpm.toDouble()))
                for (i in 1 until segment.size) {
                    lineTo(geo.xPx(segment[i].elapsedSeconds), geo.yPx(segment[i].bpm.toDouble()))
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(geo.xPx(segment.last().elapsedSeconds), geo.plotBottom)
                lineTo(geo.xPx(segment.first().elapsedSeconds), geo.plotBottom)
                close()
            }
            drawPath(area, fillColor)
            drawPath(line, color = lineColor, style = Stroke(width = strokePx))
        }

        // Tap/drag marker.
        selected?.let { idx ->
            val p = points[idx.coerceIn(points.indices)]
            val mx = geo.xPx(p.elapsedSeconds)
            val my = geo.yPx(p.bpm.toDouble())

            drawLine(
                color = markerColor.copy(alpha = 0.4f),
                start = Offset(mx, geo.topPad),
                end = Offset(mx, geo.plotBottom),
                strokeWidth = with(density) { 1.dp.toPx() },
            )
            drawCircle(ringColor, with(density) { 5.dp.toPx() }, Offset(mx, my))
            drawCircle(lineColor, with(density) { 3.5.dp.toPx() }, Offset(mx, my))

            val text = "${p.bpm} bpm · ${formatElapsed(p.elapsedSeconds)}"
            val fm = tooltipPaint.fontMetrics
            val padX = with(density) { 8.dp.toPx() }
            val padY = with(density) { 4.dp.toPx() }
            val boxW = tooltipPaint.measureText(text) + padX * 2
            val boxH = (fm.descent - fm.ascent) + padY * 2
            val boxLeft = (mx - boxW / 2).coerceIn(geo.leftPad, geo.plotRight - boxW)
            val above = my - with(density) { 10.dp.toPx() } - boxH
            val boxTop = if (above >= geo.topPad) above else my + with(density) { 10.dp.toPx() }
            drawRoundRect(
                color = tooltipBg,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(with(density) { 6.dp.toPx() }),
            )
            drawContext.canvas.nativeCanvas.drawText(
                text,
                boxLeft + padX,
                boxTop + padY - fm.ascent,
                tooltipPaint,
            )
        }
    }
}

/**
 * A compact key for the fixed [HeartRateZone] bands used by [BpmChart].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmZoneLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (zone in HeartRateZone.entries) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = zone.bandColor().copy(alpha = 0.35f),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.size(10.dp),
                    content = {},
                )
                Text(
                    text = " ${zone.rangeLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- geometry -------------------------------------------------------------

/** Pixel mapping for the plot area; built the same way from the draw and the
 *  pointer scope (both are a [Density]). */
private class ChartGeometry(
    val leftPad: Float,
    val topPad: Float,
    val plotW: Float,
    val plotH: Float,
    val xMin: Double,
    val xSpan: Double,
    val yLow: Int,
    val yHigh: Int,
) {
    val plotRight get() = leftPad + plotW
    val plotBottom get() = topPad + plotH

    fun xPx(t: Double): Float = leftPad + ((t - xMin) / xSpan * plotW).toFloat()
    fun yPx(bpm: Double): Float =
        topPad + ((yHigh - bpm) / (yHigh - yLow) * plotH).toFloat()
}

private fun chartGeometry(
    widthPx: Float,
    heightPx: Float,
    density: Density,
    points: List<BpmTrackPoint>,
): ChartGeometry = with(density) {
    val leftPad = 40.dp.toPx()
    val topPad = 8.dp.toPx()
    val bottomPad = 20.dp.toPx()
    val rightPad = 8.dp.toPx()
    val minBpm = points.minOf { it.bpm }
    val maxBpm = points.maxOf { it.bpm }
    val xMin = points.first().elapsedSeconds
    ChartGeometry(
        leftPad = leftPad,
        topPad = topPad,
        plotW = (widthPx - leftPad - rightPad).coerceAtLeast(0f),
        plotH = (heightPx - topPad - bottomPad).coerceAtLeast(0f),
        xMin = xMin,
        xSpan = (points.last().elapsedSeconds - xMin).takeIf { it > 0.0 } ?: 1.0,
        // Round the axis outwards to the next 10 with a little headroom.
        yLow = (floor((minBpm - 2) / 10.0) * 10).toInt(),
        yHigh = (ceil((maxBpm + 2) / 10.0) * 10).toInt(),
    )
}

/** Index of the sample whose time is closest to the tapped x pixel. */
private fun nearestIndex(
    points: List<BpmTrackPoint>,
    geo: ChartGeometry,
    xPx: Float,
): Int? {
    if (points.isEmpty() || geo.plotW <= 0f) return null
    val t = geo.xMin + (xPx.coerceIn(geo.leftPad, geo.plotRight) - geo.leftPad) / geo.plotW * geo.xSpan
    var best = 0
    var bestDist = Double.MAX_VALUE
    points.forEachIndexed { i, p ->
        val d = abs(p.elapsedSeconds - t)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

/** Splits [points] into contiguous runs, breaking before every gap. */
private inline fun forEachSegment(
    points: List<BpmTrackPoint>,
    action: (List<BpmTrackPoint>) -> Unit,
) {
    var start = 0
    for (i in 1..points.size) {
        if (i == points.size || points[i].gapBefore) {
            action(points.subList(start, i))
            start = i
        }
    }
}

private fun HeartRateZone.bandColor(): Color = when (this) {
    HeartRateZone.Z1 -> Color(0xFF3B82F6) // blue
    HeartRateZone.Z2 -> Color(0xFF22C55E) // green
    HeartRateZone.Z3 -> Color(0xFFEAB308) // yellow
    HeartRateZone.Z4 -> Color(0xFFF97316) // orange
    HeartRateZone.Z5 -> Color(0xFFEF4444) // red
}

private fun HeartRateZone.rangeLabel(): String = when {
    lowerBpm == 0 -> "<${upperBpm}"
    upperBpm == null -> "≥$lowerBpm"
    else -> "$lowerBpm–${upperBpm!! - 1}"
}

@Preview(showBackground = true)
@Composable
private fun BpmChartPreview() {
    val demo = buildList {
        var bpm = 78.0
        repeat(120) { i ->
            bpm += (if (i % 7 < 4) 3 else -4) + (i % 3 - 1)
            val gap = i == 70 // a synthetic reconnect gap
            add(
                BpmTrackPoint(
                    elapsedSeconds = i * 3.0 + if (i >= 70) 40 else 0,
                    bpm = bpm.coerceIn(60.0, 180.0).toInt(),
                    gapBefore = gap,
                ),
            )
        }
    }
    HeartMonitorTheme {
        BpmChart(
            points = demo,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(4.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BpmZoneLegendPreview() {
    HeartMonitorTheme {
        BpmZoneLegend(Modifier.padding(8.dp))
    }
}
