package de.hstmstr.heartmonitor.ble

/**
 * A single Heart Rate Measurement notification, already decoded from the
 * raw BLE payload of characteristic 0x2A37.
 *
 * @param bpm            heart rate in beats per minute
 * @param timestampMs    wall-clock time the sample was received (epoch millis)
 * @param sensorContact  true/false when the strap reports contact status,
 *                       null when the feature is not supported
 * @param rrIntervalsMs  RR intervals in milliseconds (may be empty)
 */
data class HeartRateSample(
    val bpm: Int,
    val timestampMs: Long = System.currentTimeMillis(),
    val sensorContact: Boolean? = null,
    val rrIntervalsMs: List<Int> = emptyList(),
)

/** High-level state of the BLE link, surfaced to the ViewModel/UI. */
sealed interface BleConnectionState {
    /** Nothing running – not scanning, not connected. */
    data object Idle : BleConnectionState

    /** Actively scanning for a device that advertises the Heart Rate service. */
    data object Scanning : BleConnectionState

    /** Device found, GATT connection / service discovery in progress. */
    data class Connecting(val deviceName: String?) : BleConnectionState

    /**
     * Link dropped unexpectedly; an automatic reconnect to the last device is
     * scheduled or in flight. A running recording keeps buffering meanwhile.
     *
     * @param attempt 1-based index of the current retry
     * @param maxAttempts total retries before giving up with [Error]
     */
    data class Reconnecting(
        val deviceName: String?,
        val attempt: Int,
        val maxAttempts: Int,
    ) : BleConnectionState

    /** Connected and receiving (or about to receive) notifications. */
    data class Connected(val deviceName: String?) : BleConnectionState

    /** Terminal error; [message] is user-presentable (German). */
    data class Error(val message: String) : BleConnectionState
}
