package de.hstmstr.heartmonitor.recording

import com.google.common.truth.Truth.assertThat
import de.hstmstr.heartmonitor.ble.HeartRateSample
import org.junit.Test

class HeartRateStatsTest {

    @Test
    fun `of returns null for empty input`() {
        assertThat(HeartRateStats.of(emptyList())).isNull()
    }

    @Test
    fun `of computes min max average and count`() {
        val stats = HeartRateStats.of(listOf(60, 80, 100))!!
        assertThat(stats.count).isEqualTo(3)
        assertThat(stats.minBpm).isEqualTo(60)
        assertThat(stats.maxBpm).isEqualTo(100)
        assertThat(stats.averageBpm).isWithin(1e-9).of(80.0)
    }

    @Test
    fun `single value has equal min max average`() {
        val stats = HeartRateStats.of(listOf(75))!!
        assertThat(stats.minBpm).isEqualTo(75)
        assertThat(stats.maxBpm).isEqualTo(75)
        assertThat(stats.averageBpm).isWithin(1e-9).of(75.0)
    }

    @Test
    fun `average is not rounded but averageBpmRounded is`() {
        val stats = HeartRateStats.of(listOf(70, 71))!!
        assertThat(stats.averageBpm).isWithin(1e-9).of(70.5)
        assertThat(stats.averageBpmRounded).isEqualTo(71) // round half up
    }

    @Test
    fun `format is the compact one-liner`() {
        val stats = HeartRateStats(count = 87, minBpm = 96, maxBpm = 152, averageBpm = 138.4)
        assertThat(stats.format()).isEqualTo("min 96 · Ø 138 · max 152")
    }

    @Test
    fun `ofSamples reads the bpm field`() {
        val samples = listOf(
            HeartRateSample(bpm = 50),
            HeartRateSample(bpm = 150),
            HeartRateSample(bpm = 100),
        )
        val stats = HeartRateStats.ofSamples(samples)!!
        assertThat(stats.minBpm).isEqualTo(50)
        assertThat(stats.maxBpm).isEqualTo(150)
        assertThat(stats.averageBpm).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `large sum does not overflow`() {
        val stats = HeartRateStats.of(List(100_000) { 200 })!!
        assertThat(stats.averageBpm).isWithin(1e-9).of(200.0)
    }

    @Test
    fun `accumulator matches batch computation`() {
        val values = listOf(72, 65, 80, 91, 77, 60, 143, 138)
        val acc = HeartRateStatsAccumulator()
        assertThat(acc.snapshot()).isNull()
        values.forEach { acc.add(it) }

        val incremental = acc.snapshot()!!
        val batch = HeartRateStats.of(values)!!
        assertThat(incremental).isEqualTo(batch)
    }

    @Test
    fun `accumulator reset clears state`() {
        val acc = HeartRateStatsAccumulator()
        acc.add(120)
        acc.reset()
        assertThat(acc.snapshot()).isNull()
        acc.add(60)
        assertThat(acc.snapshot()!!.minBpm).isEqualTo(60)
    }
}
