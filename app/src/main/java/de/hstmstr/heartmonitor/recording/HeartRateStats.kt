package de.hstmstr.heartmonitor.recording

import de.hstmstr.heartmonitor.ble.HeartRateSample
import kotlin.math.roundToInt

/**
 * Summary of the beats-per-minute values in one recording. Pure – no Android –
 * so it is directly unit-tested.
 *
 * @param count        number of samples the summary is based on
 * @param minBpm       lowest bpm seen
 * @param maxBpm       highest bpm seen
 * @param averageBpm   arithmetic mean, not rounded
 */
data class HeartRateStats(
    val count: Int,
    val minBpm: Int,
    val maxBpm: Int,
    val averageBpm: Double,
) {
    /** Mean rounded to the nearest whole bpm, for display. */
    val averageBpmRounded: Int get() = averageBpm.roundToInt()

    /** Compact one-line form, e.g. `min 58 · Ø 142 · max 176`. */
    fun format(): String = "min $minBpm · Ø $averageBpmRounded · max $maxBpm"

    companion object {
        /** Stats over raw bpm values, or null when [bpms] is empty. */
        fun of(bpms: List<Int>): HeartRateStats? {
            if (bpms.isEmpty()) return null
            var min = bpms[0]
            var max = bpms[0]
            var sum = 0L
            for (b in bpms) {
                if (b < min) min = b
                if (b > max) max = b
                sum += b
            }
            return HeartRateStats(
                count = bpms.size,
                minBpm = min,
                maxBpm = max,
                averageBpm = sum.toDouble() / bpms.size,
            )
        }

        /** Stats over the bpm field of [samples], or null when empty. */
        fun ofSamples(samples: List<HeartRateSample>): HeartRateStats? =
            of(samples.map { it.bpm })
    }
}

/**
 * Incremental min/max/mean accumulator so a live recording does not have to
 * keep every sample around just to show running statistics. Not thread-safe;
 * guard externally if updated from several threads.
 */
class HeartRateStatsAccumulator {
    private var count = 0
    private var min = Int.MAX_VALUE
    private var max = Int.MIN_VALUE
    private var sum = 0L

    fun add(bpm: Int) {
        count++
        if (bpm < min) min = bpm
        if (bpm > max) max = bpm
        sum += bpm
    }

    fun reset() {
        count = 0
        min = Int.MAX_VALUE
        max = Int.MIN_VALUE
        sum = 0L
    }

    /** Current snapshot, or null before the first [add]. */
    fun snapshot(): HeartRateStats? =
        if (count == 0) null
        else HeartRateStats(count, min, max, sum.toDouble() / count)
}
