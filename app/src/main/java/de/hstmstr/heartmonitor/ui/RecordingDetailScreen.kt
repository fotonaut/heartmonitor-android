package de.hstmstr.heartmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.hstmstr.heartmonitor.data.BpmTrackPoint
import de.hstmstr.heartmonitor.data.RecordingDetail
import de.hstmstr.heartmonitor.recording.HeartRateStats
import de.hstmstr.heartmonitor.ui.theme.HeartMonitorTheme
import java.io.File

/**
 * Detail view for one saved recording: the summary line plus a bpm-over-time
 * chart. Reached by tapping a row in [RecordingsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    file: File,
    onLoad: suspend (File) -> RecordingDetail?,
    onShare: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail by produceState<RecordingDetail?>(initialValue = null, file) {
        value = onLoad(file)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Teilen")
                    }
                },
            )
        },
    ) { innerPadding ->
        val d = detail
        when {
            d == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> RecordingDetailContent(
                detail = d,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun RecordingDetailContent(detail: RecordingDetail, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val stats = detail.summary.stats
        val duration = detail.summary.durationSeconds

        Text(
            text = buildString {
                append(detail.summary.sampleCount).append(" Messwerte")
                if (duration != null) append(" · Dauer ").append(formatElapsed(duration))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (stats != null) {
            Text(
                text = stats.format(),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        BpmChart(
            points = detail.series,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecordingDetailPreview() {
    val series = buildList {
        var bpm = 80
        repeat(90) { i ->
            bpm = (bpm + listOf(-3, -1, 2, 4).random()).coerceIn(62, 172)
            add(BpmTrackPoint(elapsedSeconds = i * 4.0, bpm = bpm))
        }
    }
    HeartMonitorTheme {
        RecordingDetailContent(
            detail = RecordingDetail(
                summary = de.hstmstr.heartmonitor.data.HeartRateCsvSummary(
                    sampleCount = series.size,
                    stats = HeartRateStats.of(series.map { it.bpm }),
                    durationSeconds = series.last().elapsedSeconds,
                ),
                series = series,
            ),
        )
    }
}
