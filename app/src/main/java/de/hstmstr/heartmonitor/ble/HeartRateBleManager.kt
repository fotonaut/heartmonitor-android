package de.hstmstr.heartmonitor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Owns the whole BLE lifecycle for a Bluetooth SIG "Heart Rate" sensor
 * (e.g. iGPSPORT HR50):
 *
 *  1. scan for peripherals advertising the Heart Rate service (0x180D)
 *  2. connect to the first match and discover its GATT services
 *  3. subscribe to the Heart Rate Measurement characteristic (0x2A37)
 *  4. decode each notification and expose it as a [HeartRateSample] via [Flow]
 *
 * All Android callbacks arrive on binder threads; results are published through
 * [StateFlow] whose `value` setter is thread-safe, so no extra synchronisation
 * is needed. The class is UI-framework agnostic – the ViewModel wires it up.
 */
class HeartRateBleManager(private val context: Context) {

    companion object {
        private const val TAG = "HeartRateBleManager"

        /** Bluetooth SIG 16-bit UUIDs expanded to their 128-bit base form. */
        val HEART_RATE_SERVICE_UUID: UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        /** Client Characteristic Configuration Descriptor – toggles notifications. */
        private val CCC_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 15_000L

        /** Name fragments that mark a device as a likely heart-rate strap. */
        private val NAME_HINTS = listOf("hr50", "igpsport", "hrm", "heart", "polar", "hr ")

        /**
         * Back-off before each automatic reconnect attempt after an unexpected
         * drop. One entry per attempt; [MAX_RECONNECT_ATTEMPTS] is its length.
         * A running recording keeps buffering across the whole window.
         */
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        private val MAX_RECONNECT_ATTEMPTS = RECONNECT_BACKOFF_MS.size
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    /** Latest decoded heart rate sample, or null until the first notification. */
    private val _heartRate = MutableStateFlow<HeartRateSample?>(null)
    val heartRate: StateFlow<HeartRateSample?> = _heartRate.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /** Set when only a name match (no advertised 0x180D) was seen; used at timeout. */
    private var fallbackCandidate: BluetoothDevice? = null

    /** Last device we actively connected to – the target for auto-reconnect. */
    private var lastDevice: BluetoothDevice? = null

    /** True while the current teardown was asked for by the user (no reconnect). */
    private var userInitiatedDisconnect = false

    /** Number of auto-reconnect attempts made since the last good connection. */
    private var reconnectAttempts = 0

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    fun isBluetoothSupported(): Boolean = adapter != null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Whether system location ("GPS toggle") is on. Only relevant on Android
     * 6 - 11: there BLE scanning silently returns nothing while location is off.
     * On 12+ we assert `neverForLocation`, so it does not matter.
     */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * Runtime permissions that must be granted before [startScan] can work.
     * Android 12+ (API 31) uses the dedicated BLUETOOTH_* permissions; older
     * versions require fine location for BLE scanning instead.
     */
    fun requiredRuntimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(): List<String> =
        requiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** Begin scanning; auto-connects to the first Heart Rate peripheral found. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = adapter
        if (adapter == null) {
            emitError("Dieses Gerät unterstützt kein Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            emitError("Bitte Bluetooth aktivieren.")
            return
        }
        if (missingPermissions().isNotEmpty()) {
            emitError("Erforderliche Berechtigungen fehlen.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            emitError("Standort ist ausgeschaltet – für den BLE-Scan nötig. Bitte aktivieren.")
            return
        }
        if (scanning) return

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            emitError("BLE-Scanner nicht verfügbar.")
            return
        }

        // Disconnect any stale link before starting fresh.
        cancelReconnect()
        closeGatt()
        _heartRate.value = null
        fallbackCandidate = null
        lastDevice = null
        userInitiatedDisconnect = false

        // Scan unfiltered and match in software: some straps advertise 0x180D
        // only in the scan response (or not at all), which a hardware
        // ScanFilter on the service UUID would silently drop.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        scanning = true
        _connectionState.value = BleConnectionState.Scanning
        try {
            scanner.startScan(/* filters = */ null, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            emitError("Scan nicht erlaubt: ${e.message}")
            return
        }

        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    /** Stop scanning and tear down any GATT connection. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        cancelReconnect()
        stopScan()
        closeGatt()
        _heartRate.value = null
        _connectionState.value = BleConnectionState.Idle
    }

    /** Call from ViewModel.onCleared(). */
    fun close() {
        userInitiatedDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
    }

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------

    private val scanTimeoutRunnable = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        val candidate = fallbackCandidate
        if (candidate != null) {
            Log.d(TAG, "No 0x180D advertiser; connecting name-matched ${candidate.address}")
            connect(candidate)
        } else if (_connectionState.value is BleConnectionState.Scanning) {
            emitError(
                "Kein Pulsgurt gefunden. Prüfen: HR50 aktiv/angelegt, " +
                    "nicht mit anderer App/Uhr verbunden, Standort eingeschaltet.",
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val advName = record?.deviceName
            val advertisedUuids = record?.serviceUuids
            Log.d(
                TAG,
                "dev=${device.address} name=$advName rssi=${result.rssi} uuids=$advertisedUuids",
            )

            val advertisesHrService =
                advertisedUuids?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true
            if (advertisesHrService) {
                stopScan()
                connect(device)
                return
            }

            // Weaker signal: name looks like a HR strap. Remember it and keep
            // scanning in case a proper 0x180D advertiser shows up.
            if (advName != null && NAME_HINTS.any { advName.contains(it, ignoreCase = true) }) {
                if (fallbackCandidate == null) {
                    Log.d(TAG, "Name match, keeping as fallback: $advName")
                }
                fallbackCandidate = device
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val hint = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan läuft bereits."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App-Registrierung fehlgeschlagen."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE-Scan wird nicht unterstützt."
                SCAN_FAILED_INTERNAL_ERROR -> "Interner Bluetooth-Fehler – Bluetooth aus/an schalten."
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                    "Bluetooth-Ressourcen erschöpft – Bluetooth aus/an schalten."
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                    "Zu viele Scans in kurzer Zeit – bitte ~30 s warten."
                else -> "Code $errorCode."
            }
            emitError("Scan fehlgeschlagen: $hint")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ---------------------------------------------------------------------
    // GATT connection
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        lastDevice = device
        userInitiatedDisconnect = false
        _connectionState.value = BleConnectionState.Connecting(device.safeName())
        gatt = try {
            device.connectGatt(context, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            emitError("Verbindung nicht erlaubt: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // Auto-reconnect
    // ---------------------------------------------------------------------

    private val reconnectRunnable = Runnable {
        val device = lastDevice
        if (userInitiatedDisconnect || device == null) return@Runnable
        Log.d(TAG, "Auto-reconnect attempt $reconnectAttempts to ${device.address}")
        connect(device)
    }

    /**
     * Called from the GATT callback after an unexpected drop. Schedules the next
     * reconnect with back-off, or emits a terminal error once attempts run out.
     */
    private fun scheduleReconnect() {
        val device = lastDevice ?: run {
            _connectionState.value = BleConnectionState.Error("Verbindung verloren.")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Auto-reconnect gave up after $reconnectAttempts attempts")
            _connectionState.value =
                BleConnectionState.Error("Verbindung verloren – kein Reconnect möglich.")
            return
        }
        val delay = RECONNECT_BACKOFF_MS[reconnectAttempts]
        reconnectAttempts++
        _connectionState.value = BleConnectionState.Reconnecting(
            deviceName = device.safeName(),
            attempt = reconnectAttempts,
            maxAttempts = MAX_RECONNECT_ATTEMPTS,
        )
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun cancelReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectAttempts = 0
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connecting(g.device.safeName())
                    // Small delay improves reliability on some phones/straps.
                    mainHandler.postDelayed({ runCatching { g.discoverServices() } }, 200)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt()
                    _heartRate.value = null
                    if (userInitiatedDisconnect) {
                        _connectionState.value = BleConnectionState.Idle
                    } else {
                        // Unexpected drop (out of range, strap off, radio glitch):
                        // keep any recording alive and retry with back-off.
                        Log.d(TAG, "Unexpected disconnect (status $status) – scheduling reconnect")
                        scheduleReconnect()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError("Dienste konnten nicht gelesen werden (Status $status).")
                return
            }
            val characteristic = g.getService(HEART_RATE_SERVICE_UUID)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (characteristic == null) {
                emitError("Herzfrequenz-Merkmal (0x2A37) nicht gefunden.")
                return
            }

            g.setCharacteristicNotification(characteristic, true)

            val cccd = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (cccd == null) {
                // No descriptor: some sensors still push notifications anyway.
                markConnected(g)
                return
            }

            val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enableValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enableValue
                    g.writeDescriptor(cccd)
                }
            }
            if (!ok) emitError("Benachrichtigungen konnten nicht aktiviert werden.")
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCC_DESCRIPTOR_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markConnected(g)
            } else {
                emitError("Benachrichtigungen aktivieren fehlgeschlagen (Status $status).")
            }
        }

        // Android 13+ delivers the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                HeartRateMeasurementParser.decode(value)?.let { _heartRate.value = it }
            }
        }

        // Android 12 and below.
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val value = characteristic.value ?: return
                HeartRateMeasurementParser.decode(value)?.let { _heartRate.value = it }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Reached a live subscription: publish [BleConnectionState.Connected] and
     *  clear the auto-reconnect counter so the next drop starts fresh. */
    private fun markConnected(g: BluetoothGatt) {
        reconnectAttempts = 0
        _connectionState.value = BleConnectionState.Connected(g.device.safeName())
    }

    private fun emitError(message: String) {
        Log.w(TAG, message)
        _connectionState.value = BleConnectionState.Error(message)
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String? =
        try {
            name
        } catch (_: SecurityException) {
            null
        }
}
