package de.hstmstr.heartmonitor

import android.app.Application
import de.hstmstr.heartmonitor.ble.HeartRateBleManager
import de.hstmstr.heartmonitor.data.CsvStorageManager
import de.hstmstr.heartmonitor.recording.HeartRateRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the process-scoped singletons. The BLE link and the recording buffer
 * must outlive any single Activity/ViewModel so that an in-progress recording
 * keeps running while the app is backgrounded (see [de.hstmstr.heartmonitor.recording.RecordingService]).
 */
class HeartMonitorApp : Application() {

    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val bleManager: HeartRateBleManager by lazy { HeartRateBleManager(this) }

    val recorder: HeartRateRecorder by lazy {
        HeartRateRecorder(
            scope = appScope,
            heartRate = bleManager.heartRate,
            connectionState = bleManager.connectionState,
            storage = CsvStorageManager(this),
        )
    }
}
