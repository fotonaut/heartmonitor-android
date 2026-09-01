package de.hstmstr.heartmonitor.ble

import kotlin.math.roundToInt

/**
 * Decoder for the Bluetooth SIG "Heart Rate Measurement" characteristic
 * (0x2A37). Pure – no Android dependencies – so it can be unit-tested directly.
 *
 * Payload layout:
 *   byte 0        flags
 *     bit 0       value format: 0 = UINT8, 1 = UINT16
 *     bit 1..2    sensor contact status (bit2 = supported, bit1 = detected)
 *     bit 3       energy expended field present
 *     bit 4       RR-interval field(s) present
 *   byte 1..2     heart rate value (UINT8 or UINT16, little-endian)
 *   [2 bytes]     energy expended (UINT16) – optional
 *   [n*2 bytes]   RR intervals (UINT16, units of 1/1024 s) – optional
 */
object HeartRateMeasurementParser {

    /**
     * Decodes one notification payload. Returns null when [data] is empty or
     * too short for the fields its flags announce. The returned sample carries
     * the default (current) timestamp – callers that need the receive time
     * should stamp it themselves.
     */
    fun decode(data: ByteArray): HeartRateSample? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val is16Bit = flags and 0x01 == 0x01
        var offset = 1

        val bpm: Int = if (is16Bit) {
            if (data.size < offset + 2) return null
            val v = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            v
        } else {
            if (data.size < offset + 1) return null
            val v = data[offset].toInt() and 0xFF
            offset += 1
            v
        }

        val contactSupported = flags and 0x04 == 0x04
        val contactDetected = flags and 0x02 == 0x02
        val sensorContact = if (contactSupported) contactDetected else null

        if (flags and 0x08 == 0x08) offset += 2 // skip energy expended

        val rr = ArrayList<Int>()
        if (flags and 0x10 == 0x10) {
            while (data.size >= offset + 2) {
                val raw = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                rr += (raw * 1000.0 / 1024.0).roundToInt()
                offset += 2
            }
        }

        return HeartRateSample(bpm = bpm, sensorContact = sensorContact, rrIntervalsMs = rr)
    }
}
