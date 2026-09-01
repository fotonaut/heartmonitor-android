package de.hstmstr.heartmonitor.data

import com.google.common.truth.Truth.assertThat
import de.hstmstr.heartmonitor.ble.HeartRateSample
import org.junit.Test
import java.time.ZoneId

class HeartRateCsvSummaryTest {

    private val utc = ZoneId.of("UTC")
    private val startMs = 1_700_000_000_000L

    @Test
    fun `round-trips the stats of a document written by HeartRateCsv`() {
        val samples = listOf(
            HeartRateSample(bpm = 60, timestampMs = startMs),
            HeartRateSample(bpm = 150, timestampMs = startMs + 30_000, rrIntervalsMs = listOf(400, 410)),
            HeartRateSample(bpm = 90, timestampMs = startMs + 60_000, sensorContact = true),
        )
        val csv = HeartRateCsv.build(samples, utc, startMs)

        val summary = HeartRateCsvSummary.parse(csv)

        assertThat(summary.sampleCount).isEqualTo(3)
        assertThat(summary.stats!!.minBpm).isEqualTo(60)
        assertThat(summary.stats!!.maxBpm).isEqualTo(150)
        assertThat(summary.stats!!.averageBpm).isWithin(1e-9).of(100.0)
        assertThat(summary.durationSeconds).isWithin(1e-9).of(60.0)
    }

    @Test
    fun `header-only document has no samples and no stats`() {
        val summary = HeartRateCsvSummary.parse(HeartRateCsv.HEADER + "\n")
        assertThat(summary.sampleCount).isEqualTo(0)
        assertThat(summary.stats).isNull()
        assertThat(summary.durationSeconds).isNull()
    }

    @Test
    fun `empty string yields an empty summary`() {
        val summary = HeartRateCsvSummary.parse("")
        assertThat(summary.sampleCount).isEqualTo(0)
        assertThat(summary.stats).isNull()
        assertThat(summary.durationSeconds).isNull()
    }

    @Test
    fun `single sample has stats but no duration`() {
        val csv = HeartRateCsv.build(listOf(HeartRateSample(bpm = 77, timestampMs = startMs)), utc, startMs)
        val summary = HeartRateCsvSummary.parse(csv)
        assertThat(summary.sampleCount).isEqualTo(1)
        assertThat(summary.stats!!.averageBpm).isWithin(1e-9).of(77.0)
        assertThat(summary.durationSeconds).isNull()
    }

    @Test
    fun `blank and truncated lines are skipped`() {
        val csv = buildString {
            append(HeartRateCsv.HEADER).append('\n')
            append("2023-11-14T22:13:20.000Z,1700000000000,0.00,60,,\n")
            append('\n')
            append("garbage,without,enough\n")
            append("2023-11-14T22:13:30.000Z,1700000010000,10.00,80,,\n")
        }
        val summary = HeartRateCsvSummary.parse(csv)
        assertThat(summary.sampleCount).isEqualTo(2)
        assertThat(summary.stats!!.minBpm).isEqualTo(60)
        assertThat(summary.stats!!.maxBpm).isEqualTo(80)
        assertThat(summary.durationSeconds).isWithin(1e-9).of(10.0)
    }

    @Test
    fun `a row with a non-numeric bpm is ignored`() {
        val csv = buildString {
            append(HeartRateCsv.HEADER).append('\n')
            append("2023-11-14T22:13:20.000Z,1700000000000,0.00,--,,\n")
            append("2023-11-14T22:13:21.000Z,1700000001000,1.00,72,,\n")
        }
        val summary = HeartRateCsvSummary.parse(csv)
        assertThat(summary.sampleCount).isEqualTo(1)
        assertThat(summary.stats!!.minBpm).isEqualTo(72)
        // Only one timestamped bpm row -> no span.
        assertThat(summary.durationSeconds).isNull()
    }

    @Test
    fun `document without a trailing newline still parses the last row`() {
        val csv = HeartRateCsv.HEADER + "\n" +
            "2023-11-14T22:13:20.000Z,1700000000000,0.00,64,,\n" +
            "2023-11-14T22:13:25.000Z,1700000005000,5.00,68,,"
        val summary = HeartRateCsvSummary.parse(csv)
        assertThat(summary.sampleCount).isEqualTo(2)
        assertThat(summary.durationSeconds).isWithin(1e-9).of(5.0)
    }
}
