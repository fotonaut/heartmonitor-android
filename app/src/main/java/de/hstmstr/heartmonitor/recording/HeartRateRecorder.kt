package de.hstmstr.heartmonitor.recording

import android.util.Log
import de.hstmstr.heartmonitor.ble.BleConnectionState
import de.hstmstr.heartmonitor.ble.HeartRateSample
import de.hstmstr.heartmonitor.data.CsvStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Observable recording state. */
data class RecordingState(
    val isRecording: Boolean = false,
    val sampleCount: Int = 0,
    val startedAtMs: Long? = null,
    val lastSavedFile: String? = null,
    /**
     * Running min/max/average bpm. Updates live while [isRecording] and holds
     * the final summary of the last session after it stops (null until the
     * first sample of the first recording).
     */
    val stats: HeartRateStats? = null,
)

/**
 * Application-scoped: collects [HeartRateSample]s into a buffer while a
 * recording is active and writes them to CSV on stop. Kept out of the
 * ViewModel so a recording (and the buffer) survives configuration changes
 * and – together with [RecordingService] – the app going to the background.
 */
class HeartRateRecorder(
    private val scope: CoroutineScope,
    heartRate: StateFlow<HeartRateSample?>,
    connectionState: StateFlow<BleConnectionState>,
    private val storage: CsvStorageManager,
) {
    private companion object {
        const val TAG = "HeartRateRecorder"
    }

    private val buffer = ArrayList<HeartRateSample>()
    private val stats = HeartRateStatsAccumulator()
    private val lock = Any()

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** One-shot user messages (snackbar / toast). */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        scope.launch {
            heartRate.collect { sample ->
                if (sample != null && _state.value.isRecording) {
                    val (size, snapshot) = synchronized(lock) {
                        buffer.add(sample)
                        stats.add(sample.bpm)
                        buffer.size to stats.snapshot()
                    }
                    _state.update { it.copy(sampleCount = size, stats = snapshot) }
                }
            }
        }
        scope.launch {
            connectionState.collect { state ->
                // Reconnecting is deliberately NOT terminal: the BLE manager is
                // retrying with back-off and the buffer keeps filling once the
                // strap is back. Only a user disconnect (Idle) or an exhausted
                // /failed reconnect (Error) ends the recording.
                val lost = state is BleConnectionState.Idle || state is BleConnectionState.Error
                if (lost && _state.value.isRecording) {
                    stop(reason = "Verbindung verloren – Aufzeichnung beendet.")
                }
            }
        }
    }

    fun start() {
        if (_state.value.isRecording) return
        synchronized(lock) {
            buffer.clear()
            stats.reset()
        }
        _state.value = RecordingState(isRecording = true, startedAtMs = System.currentTimeMillis())
        _events.tryEmit("Aufzeichnung gestartet.")
    }

    /**
     * Stops recording and persists the buffer. Safe to call when not recording
     * (no-op). [reason] is prepended to the resulting message.
     */
    fun stop(reason: String? = null) {
        if (!_state.value.isRecording) return
        val samples = synchronized(lock) { buffer.toList() }
        val summary = HeartRateStats.ofSamples(samples)
        _state.update { it.copy(isRecording = false, stats = summary) }

        val prefix = reason?.let { "$it " } ?: ""
        if (samples.isEmpty()) {
            _events.tryEmit("${prefix}Keine Daten aufgezeichnet.")
            return
        }
        val statsSuffix = summary?.let { " · ${it.format()}" } ?: ""
        scope.launch {
            runCatching { storage.save(samples) }
                .onSuccess { result ->
                    _state.update { it.copy(lastSavedFile = result.fileName) }
                    _events.tryEmit(
                        "${prefix}CSV gespeichert (${result.sampleCount} Werte$statsSuffix): " +
                            result.displayLocation,
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "CSV export failed", e)
                    _events.tryEmit("${prefix}CSV-Export fehlgeschlagen: ${e.message}")
                }
        }
    }
}
