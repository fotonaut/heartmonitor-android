package de.hstmstr.heartmonitor.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hstmstr.heartmonitor.HeartMonitorApp
import de.hstmstr.heartmonitor.ble.BleConnectionState
import de.hstmstr.heartmonitor.data.CsvStorageManager
import de.hstmstr.heartmonitor.recording.HeartRateStats
import de.hstmstr.heartmonitor.recording.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Immutable snapshot the Compose UI renders.
 */
data class HeartRateUiState(
    val connection: BleConnectionState = BleConnectionState.Idle,
    val bpm: Int? = null,
    val sensorContact: Boolean? = null,
    val lastUpdateMs: Long? = null,
    val isRecording: Boolean = false,
    val recordedSampleCount: Int = 0,
    val lastSavedFile: String? = null,
    /** Running (or last completed) min/max/average bpm of a recording. */
    val stats: HeartRateStats? = null,
    /** Transient one-shot message for a snackbar; cleared via [HeartRateViewModel.consumeMessage]. */
    val message: String? = null,
) {
    val isConnected: Boolean get() = connection is BleConnectionState.Connected
    val isBusy: Boolean
        get() = connection is BleConnectionState.Scanning ||
            connection is BleConnectionState.Connecting ||
            connection is BleConnectionState.Reconnecting

    /** Label for the scan/connect button. */
    val connectButtonLabel: String
        get() = when (connection) {
            is BleConnectionState.Scanning -> "Suche abbrechen"
            is BleConnectionState.Connecting -> "Verbindung abbrechen"
            is BleConnectionState.Reconnecting -> "Reconnect abbrechen"
            is BleConnectionState.Connected -> "Trennen"
            else -> "Scannen / Verbinden"
        }
}

/**
 * Thin adapter over the process-scoped [HeartMonitorApp.bleManager] /
 * [HeartMonitorApp.recorder]: exposes one [uiState] stream, forwards the
 * connect/disconnect actions and starts/stops [RecordingService].
 */
class HeartRateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HeartMonitorApp
    private val bleManager = app.bleManager
    private val recorder = app.recorder
    private val csvStorage = CsvStorageManager(application)

    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HeartRateUiState> = combine(
        bleManager.connectionState,
        bleManager.heartRate,
        recorder.state,
        _message,
    ) { connection, sample, recording, message ->
        HeartRateUiState(
            connection = connection,
            bpm = sample?.bpm,
            sensorContact = sample?.sensorContact,
            lastUpdateMs = sample?.timestampMs,
            isRecording = recording.isRecording,
            recordedSampleCount = recording.sampleCount,
            lastSavedFile = recording.lastSavedFile,
            stats = recording.stats,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HeartRateUiState(),
    )

    init {
        // Surface recorder messages (start / saved / failed) through the snackbar.
        viewModelScope.launch {
            recorder.events.collect { _message.value = it }
        }
    }

    // -----------------------------------------------------------------
    // Connection actions
    // -----------------------------------------------------------------

    fun isBluetoothReady(): Boolean =
        bleManager.isBluetoothSupported() && bleManager.isBluetoothEnabled()

    fun requiredPermissions(): Array<String> = bleManager.requiredRuntimePermissions()

    fun hasAllPermissions(): Boolean = bleManager.missingPermissions().isEmpty()

    /** Single entry point for the scan/connect button (toggles by state). */
    fun onConnectButtonClicked() {
        when (uiState.value.connection) {
            is BleConnectionState.Scanning,
            is BleConnectionState.Connecting,
            is BleConnectionState.Reconnecting,
            is BleConnectionState.Connected -> bleManager.disconnect()

            else -> startScan()
        }
    }

    /** Call after the runtime permission request completed. */
    fun onPermissionsResult(allGranted: Boolean) {
        if (allGranted) {
            startScan()
        } else {
            _message.value = "Ohne Bluetooth-Berechtigungen ist kein Scan möglich."
        }
    }

    private fun startScan() {
        if (!bleManager.isBluetoothSupported()) {
            _message.value = "Dieses Gerät unterstützt kein Bluetooth LE."
            return
        }
        if (!bleManager.isBluetoothEnabled()) {
            _message.value = "Bitte zuerst Bluetooth einschalten."
            return
        }
        bleManager.startScan()
    }

    // -----------------------------------------------------------------
    // Recording actions – delegated to RecordingService
    // -----------------------------------------------------------------

    fun onRecordButtonClicked() {
        if (recorder.state.value.isRecording) {
            RecordingService.stop(getApplication())
            return
        }
        if (bleManager.connectionState.value !is BleConnectionState.Connected) {
            _message.value = "Erst mit dem Pulsgurt verbinden."
            return
        }
        RecordingService.start(getApplication())
    }

    // -----------------------------------------------------------------
    // Saved recordings
    // -----------------------------------------------------------------

    fun listRecordings(): List<File> = csvStorage.listRecordings()

    fun deleteRecording(file: File): Boolean = csvStorage.delete(file)

    /** ACTION_SEND intent with a FileProvider content:// URI for [file]. */
    fun shareIntentFor(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // -----------------------------------------------------------------

    /** Clear the transient message once the UI has shown it. */
    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        // BLE + recorder are app-scoped and must survive; only drop an idle
        // connection when the user actually leaves the app without recording.
        if (!recorder.state.value.isRecording) {
            bleManager.disconnect()
        }
        super.onCleared()
    }
}
