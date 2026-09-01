package de.hstmstr.heartmonitor.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for the Bluetooth SIG "Heart Rate Measurement" (0x2A37) decoder. */
class HeartRateMeasurementParserTest {

    private fun bytes(vararg ints: Int): ByteArray =
        ByteArray(ints.size) { ints[it].toByte() }

    @Test
    fun `empty payload returns null`() {
        assertThat(HeartRateMeasurementParser.decode(ByteArray(0))).isNull()
    }

    @Test
    fun `uint8 value, no optional fields`() {
        // flags = 0x00, bpm = 0x50 (80)
        val sample = HeartRateMeasurementParser.decode(bytes(0x00, 0x50))!!
        assertThat(sample.bpm).isEqualTo(80)
        assertThat(sample.sensorContact).isNull()
        assertThat(sample.rrIntervalsMs).isEmpty()
    }

    @Test
    fun `uint16 value is little-endian`() {
        // flags = 0x01 (16-bit), value = 0x012C (300)
        val sample = HeartRateMeasurementParser.decode(bytes(0x01, 0x2C, 0x01))!!
        assertThat(sample.bpm).isEqualTo(300)
    }

    @Test
    fun `uint8 value with missing value byte returns null`() {
        assertThat(HeartRateMeasurementParser.decode(bytes(0x00))).isNull()
    }

    @Test
    fun `uint16 value with only one value byte returns null`() {
        assertThat(HeartRateMeasurementParser.decode(bytes(0x01, 0x2C))).isNull()
    }

    @Test
    fun `sensor contact supported and detected`() {
        // flags = 0x06 -> bit2 supported, bit1 detected
        val sample = HeartRateMeasurementParser.decode(bytes(0x06, 0x48))!!
        assertThat(sample.bpm).isEqualTo(72)
        assertThat(sample.sensorContact).isTrue()
    }

    @Test
    fun `sensor contact supported but not detected`() {
        // flags = 0x04 -> bit2 supported, bit1 clear
        val sample = HeartRateMeasurementParser.decode(bytes(0x04, 0x48))!!
        assertThat(sample.sensorContact).isFalse()
    }

    @Test
    fun `sensor contact not supported is reported as null even if detected bit set`() {
        // flags = 0x02 -> bit1 set, bit2 (supported) clear
        val sample = HeartRateMeasurementParser.decode(bytes(0x02, 0x48))!!
        assertThat(sample.sensorContact).isNull()
    }

    @Test
    fun `energy expended field is skipped`() {
        // flags = 0x08 -> energy expended present (2 bytes), no RR
        val sample = HeartRateMeasurementParser.decode(bytes(0x08, 0x50, 0xFF, 0xFF))!!
        assertThat(sample.bpm).isEqualTo(80)
        assertThat(sample.rrIntervalsMs).isEmpty()
    }

    @Test
    fun `single RR interval is converted from 1024ths to milliseconds`() {
        // flags = 0x10 -> RR present; raw = 0x0400 (1024) -> 1000 ms
        val sample = HeartRateMeasurementParser.decode(bytes(0x10, 0x50, 0x00, 0x04))!!
        assertThat(sample.rrIntervalsMs).containsExactly(1000)
    }

    @Test
    fun `multiple RR intervals are decoded in order`() {
        // raw 0x0400 (1024) -> 1000 ms, raw 0x0200 (512) -> 500 ms
        val sample = HeartRateMeasurementParser.decode(
            bytes(0x10, 0x50, 0x00, 0x04, 0x00, 0x02),
        )!!
        assertThat(sample.rrIntervalsMs).containsExactly(1000, 500).inOrder()
    }

    @Test
    fun `RR conversion rounds to nearest millisecond`() {
        // raw 0x0133 (307) -> 307 * 1000 / 1024 = 299.8 -> 300
        val sample = HeartRateMeasurementParser.decode(bytes(0x10, 0x50, 0x33, 0x01))!!
        assertThat(sample.rrIntervalsMs).containsExactly(300)
    }

    @Test
    fun `energy expended and RR intervals combined`() {
        // flags = 0x18 -> energy expended (2 bytes) then RR
        val sample = HeartRateMeasurementParser.decode(
            bytes(0x18, 0x50, 0xAA, 0xBB, 0x00, 0x04),
        )!!
        assertThat(sample.bpm).isEqualTo(80)
        assertThat(sample.rrIntervalsMs).containsExactly(1000)
    }

    @Test
    fun `trailing odd RR byte is ignored`() {
        // flags = 0x10, one full RR pair then a stray byte
        val sample = HeartRateMeasurementParser.decode(
            bytes(0x10, 0x50, 0x00, 0x04, 0x07),
        )!!
        assertThat(sample.rrIntervalsMs).containsExactly(1000)
    }

    @Test
    fun `real HR50 style frame - 16bit with contact and RR`() {
        // flags = 0x17: 16-bit value, contact supported+detected, RR present
        val sample = HeartRateMeasurementParser.decode(
            bytes(0x17, 0x41, 0x00, 0x00, 0x04),
        )!!
        assertThat(sample.bpm).isEqualTo(65)
        assertThat(sample.sensorContact).isTrue()
        assertThat(sample.rrIntervalsMs).containsExactly(1000)
    }
}
