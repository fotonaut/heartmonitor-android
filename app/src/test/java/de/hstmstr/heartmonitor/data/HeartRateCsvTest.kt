package de.hstmstr.heartmonitor.data

import com.google.common.truth.Truth.assertThat
import de.hstmstr.heartmonitor.ble.HeartRateSample
import org.junit.After
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class HeartRateCsvTest {

    private val utc = ZoneId.of("UTC")

    // 2023-11-14T22:13:20.000Z
    private val startMs = 1_700_000_000_000L

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `file name uses the start timestamp in the given zone`() {
        assertThat(HeartRateCsv.fileName(startMs, utc)).isEqualTo("hr_2023-11-14_22-13-20.csv")
    }

    @Test
    fun `full document matches the expected byte layout`() {
        val samples = listOf(
            HeartRateSample(bpm = 60, timestampMs = startMs, sensorContact = null),
            HeartRateSample(
                bpm = 62,
                timestampMs = startMs + 1_500,
                sensorContact = true,
                rrIntervalsMs = listOf(800, 810),
            ),
            HeartRateSample(bpm = 65, timestampMs = startMs + 12_340, sensorContact = false),
        )

        val csv = HeartRateCsv.build(samples, utc, startMs)

        val expected = buildString {
            append("timestamp_iso,timestamp_epoch_ms,elapsed_s,bpm,sensor_contact,rr_ms\n")
            append("2023-11-14T22:13:20.000Z,1700000000000,0.00,60,,\n")
            append("2023-11-14T22:13:21.500Z,1700000001500,1.50,62,true,800 810\n")
            append("2023-11-14T22:13:32.340Z,1700000012340,12.34,65,false,\n")
        }
        assertThat(csv).isEqualTo(expected)
    }

    @Test
    fun `first line is the header and line count is samples plus one`() {
        val samples = (0 until 5).map {
            HeartRateSample(bpm = 70 + it, timestampMs = startMs + it * 1_000L)
        }
        val lines = HeartRateCsv.build(samples, utc, startMs).trimEnd('\n').split('\n')
        assertThat(lines.first()).isEqualTo(HeartRateCsv.HEADER)
        assertThat(lines).hasSize(samples.size + 1)
    }

    @Test
    fun `elapsed seconds always use a dot even under a comma-decimal locale`() {
        Locale.setDefault(Locale.GERMANY)
        val samples = listOf(
            HeartRateSample(bpm = 60, timestampMs = startMs),
            HeartRateSample(bpm = 61, timestampMs = startMs + 2_500),
        )
        val csv = HeartRateCsv.build(samples, utc, startMs)
        assertThat(csv).contains(",2.50,61,")
    }

    @Test
    fun `elapsed can be negative when a sample predates the reference`() {
        val samples = listOf(
            HeartRateSample(bpm = 60, timestampMs = startMs - 500),
        )
        val csv = HeartRateCsv.build(samples, utc, startMs)
        assertThat(csv.trimEnd('\n').split('\n')[1]).contains(",-0.50,60,,")
    }
}
