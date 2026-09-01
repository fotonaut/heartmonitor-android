package de.hstmstr.heartmonitor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hstmstr.heartmonitor.ble.BleConnectionState
import de.hstmstr.heartmonitor.recording.HeartRateStats
import de.hstmstr.heartmonitor.ui.theme.HeartMonitorTheme

/**
 * Minimalist single-screen UI:
 *  - connection status line
 *  - large BPM read-out with a pulsing heart
 *  - scan/connect button
 *  - start/stop recording button
 *  - transient messages via snackbar
 *
 * Stateless: everything comes from [state] and is driven back through the
 * three callbacks, so it previews and tests without a ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateScreen(
    state: HeartRateUiState,
    onConnectClick: () -> Unit,
    onRecordClick: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRecordings: () -> Unit = {},
    onChooseDevice: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onMessageShown()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("HeartMonitor") },
                actions = {
                    IconButton(onClick = onOpenRecordings) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = "Aufzeichnungen",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            ConnectionStatus(state)

            BpmReadout(
                bpm = state.bpm,
                animate = state.isConnected && state.bpm != null,
                sensorContact = state.sensorContact,
            )

            RecordingInfo(state)

            ActionButtons(
                state = state,
                onConnectClick = onConnectClick,
                onRecordClick = onRecordClick,
                onChooseDevice = onChooseDevice,
            )
        }
    }
}

@Composable
private fun ConnectionStatus(state: HeartRateUiState) {
    val (label, color) = when (val c = state.connection) {
        is BleConnectionState.Idle ->
            "Nicht verbunden" to MaterialTheme.colorScheme.onSurfaceVariant
        is BleConnectionState.Scanning ->
            "Suche Pulsgurt…" to MaterialTheme.colorScheme.onSurfaceVariant
        is BleConnectionState.Connecting ->
            "Verbinde${c.deviceName?.let { " mit $it" } ?: ""}…" to MaterialTheme.colorScheme.onSurfaceVariant
        is BleConnectionState.Reconnecting ->
            "Verbindung verloren – neuer Versuch ${c.attempt}/${c.maxAttempts}…" to
                MaterialTheme.colorScheme.error
        is BleConnectionState.Connected ->
            "Verbunden${c.deviceName?.let { " mit $it" } ?: ""}" to MaterialTheme.colorScheme.primary
        is BleConnectionState.Error ->
            c.message to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BpmReadout(
    bpm: Int?,
    animate: Boolean,
    sensorContact: Boolean?,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (animate) 1.14f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(72.dp)
                .scale(scale),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = bpm?.toString() ?: "––",
            fontSize = 112.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "BPM",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sensorContact == false) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Kein Hautkontakt",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RecordingInfo(state: HeartRateUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.isRecording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Aufzeichnung läuft · ${state.recordedSampleCount} Werte",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        state.stats?.let { s ->
            Text(
                text = if (state.isRecording) s.format() else "Letzte Aufzeichnung: ${s.format()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.lastSavedFile?.let {
            Text(
                text = "Zuletzt gespeichert: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionButtons(
    state: HeartRateUiState,
    onConnectClick: () -> Unit,
    onRecordClick: () -> Unit,
    onChooseDevice: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onConnectClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(state.connectButtonLabel)
        }

        if (!state.isConnected && !state.isRecording) {
            TextButton(
                onClick = onChooseDevice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.rememberedDeviceName != null) "Anderes Gerät wählen"
                    else "Gerät aus Liste wählen",
                )
            }
        }

        OutlinedButton(
            onClick = onRecordClick,
            enabled = state.isConnected || state.isRecording,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = if (state.isRecording) {
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Text(
                if (state.isRecording) "Aufzeichnung stoppen (CSV speichern)"
                else "Aufzeichnung starten",
            )
        }
    }
}

// ---------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------

@Preview(showBackground = true, name = "Verbunden + Aufnahme")
@Composable
private fun PreviewRecording() {
    HeartMonitorTheme {
        HeartRateScreen(
            state = HeartRateUiState(
                connection = BleConnectionState.Connected("HR50"),
                bpm = 138,
                sensorContact = true,
                isRecording = true,
                recordedSampleCount = 87,
                stats = HeartRateStats(count = 87, minBpm = 96, maxBpm = 152, averageBpm = 138.4),
                lastSavedFile = "hr_2026-09-01_14-30-05.csv",
            ),
            onConnectClick = {},
            onRecordClick = {},
            onMessageShown = {},
        )
    }
}

@Preview(showBackground = true, name = "Reconnect während Aufnahme")
@Composable
private fun PreviewReconnecting() {
    HeartMonitorTheme {
        HeartRateScreen(
            state = HeartRateUiState(
                connection = BleConnectionState.Reconnecting("HR50", attempt = 2, maxAttempts = 5),
                bpm = null,
                isRecording = true,
                recordedSampleCount = 213,
                stats = HeartRateStats(count = 213, minBpm = 88, maxBpm = 171, averageBpm = 142.0),
            ),
            onConnectClick = {},
            onRecordClick = {},
            onMessageShown = {},
        )
    }
}

@Preview(showBackground = true, name = "Getrennt")
@Composable
private fun PreviewIdle() {
    HeartMonitorTheme {
        HeartRateScreen(
            state = HeartRateUiState(connection = BleConnectionState.Idle),
            onConnectClick = {},
            onRecordClick = {},
            onMessageShown = {},
        )
    }
}
