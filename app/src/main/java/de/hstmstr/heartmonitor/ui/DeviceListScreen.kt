package de.hstmstr.heartmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.hstmstr.heartmonitor.ble.DiscoveredDevice
import de.hstmstr.heartmonitor.ui.theme.HeartMonitorTheme

/**
 * Device picker: the peripherals seen during the current BLE scan. Tapping one
 * connects to it and remembers it for next time. Stateless – everything is
 * driven by [devices] / [scanning] and the callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    devices: List<DiscoveredDevice>,
    scanning: Boolean,
    rememberedAddress: String?,
    onPick: (String) -> Unit,
    onRescan: () -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Gerät wählen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onRescan) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Erneut suchen")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            devices.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (scanning) "Suche Geräte…"
                        else "Keine Geräte gefunden.\nPulssensor aktiv, angelegt und nicht mit " +
                            "einer anderen App/Uhr verbunden?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!scanning) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRescan) { Text("Erneut suchen") }
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceRow(
                        device = device,
                        remembered = device.address == rememberedAddress,
                        onClick = { onPick(device.address) },
                    )
                }
                if (rememberedAddress != null) {
                    item {
                        TextButton(
                            onClick = onForget,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text("Gemerktes Gerät vergessen") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    remembered: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(device.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.address, style = MaterialTheme.typography.bodySmall)
                if (device.advertisesHrService) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "Herzfrequenz-Dienst",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("HF-Dienst", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        leadingContent = {
            Icon(
                if (remembered) Icons.Filled.Star else Icons.Filled.Bluetooth,
                contentDescription = if (remembered) "Zuletzt genutzt" else null,
                tint = if (remembered) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

// ---------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------

@Preview(showBackground = true, name = "Geräte gefunden")
@Composable
private fun DeviceListPreview() {
    HeartMonitorTheme {
        DeviceListScreen(
            devices = listOf(
                DiscoveredDevice("AA:BB:CC:DD:EE:01", "HR50-6509886", -58, advertisesHrService = true),
                DiscoveredDevice("AA:BB:CC:DD:EE:02", "Polar H10", -74, advertisesHrService = true),
                DiscoveredDevice("AA:BB:CC:DD:EE:03", "Irgendein BLE-Ding", -88, advertisesHrService = false),
            ),
            scanning = true,
            rememberedAddress = "AA:BB:CC:DD:EE:01",
            onPick = {},
            onRescan = {},
            onForget = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Nichts gefunden")
@Composable
private fun DeviceListEmptyPreview() {
    HeartMonitorTheme {
        DeviceListScreen(
            devices = emptyList(),
            scanning = false,
            rememberedAddress = null,
            onPick = {},
            onRescan = {},
            onForget = {},
            onBack = {},
        )
    }
}
