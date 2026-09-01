package de.hstmstr.heartmonitor.data

import de.hstmstr.heartmonitor.recording.HeartRateStats

/**
 * One point of a recording's bpm-over-time track, for the detail chart.
 *
 * @param elapsedSeconds  seconds since the first sample (the CSV `elapsed_s`
 *                        column), or a 0-based index when that column is unusable
 * @param bpm             heart rate at that point
 * @param gapBefore       true when a long jump in elapsed time precedes this
 *                        point (a recording gap, e.g. an auto-reconnect) – the
 *                        chart breaks the line here instead of drawing a long
 *                        diagonal. Always false in index-fallback mode.
 */
data class BpmTrackPoint(
    val elapsedSeconds: Double,
    val bpm: Int,
    val gapBefore: Boolean = false,
)

/**
 * Aggregate view of a stored recording, recovered by re-parsing the CSV that
 * [HeartRateCsv] wrote. Pure – no Android, no I/O – so it is directly
 * unit-tested; [CsvStorageManager.summarize] does the file read.
 *
 * @param sampleCount     rows with a valid bpm value
 * @param stats           min/max/average bpm, or null when no bpm row parsed
 * @param durationSeconds  wall-clock span between the first and last sample's
 *                         `timestamp_epoch_ms`; null with fewer than two
 *                         timestamped rows
 */
data class HeartRateCsvSummary(
    val sampleCount: Int,
    val stats: HeartRateStats?,
    val durationSeconds: Double?,
) {
    companion object {
        // Column order of HeartRateCsv.HEADER.
        private const val COL_EPOCH_MS = 1
        private const val COL_ELAPSED_S = 2
        private const val COL_BPM = 3

        // A jump in elapsed_s larger than this counts as a recording gap: long
        // enough to ignore a few skipped beats, short enough to catch a
        // reconnect (backoff tops out at 15 s over 5 attempts).
        private const val GAP_MIN_SECONDS = 8.0
        private const val GAP_MEDIAN_FACTOR = 6.0

        /** One parsed data row; the header, blank and short lines never reach this. */
        private class Row(val epochMs: Long?, val elapsedS: Double?, val bpm: Int)

        private fun rows(csv: String): List<Row> {
            val out = ArrayList<Row>()
            for (line in csv.lineSequence()) {
                if (line.isBlank()) continue
                val cols = line.split(',')
                if (cols.size <= COL_BPM) continue
                // Non-numeric bpm (the "bpm" header cell, a corrupt row) -> skip.
                val bpm = cols[COL_BPM].trim().toIntOrNull() ?: continue
                out.add(
                    Row(
                        epochMs = cols[COL_EPOCH_MS].trim().toLongOrNull(),
                        elapsedS = cols[COL_ELAPSED_S].trim().toDoubleOrNull(),
                        bpm = bpm,
                    ),
                )
            }
            return out
        }

        /**
         * Parses the text of a [HeartRateCsv] document. The header line and any
         * blank or too-short line are skipped, so a truncated file still yields
         * a best-effort summary.
         */
        fun parse(csv: String): HeartRateCsvSummary {
            val rows = rows(csv)
            val epochs = rows.mapNotNull { it.epochMs }
            val duration = epochs.firstOrNull()
                ?.let { first -> epochs.last().takeIf { it != first } }
                ?.let { (it - epochs.first()) / 1000.0 }

            return HeartRateCsvSummary(
                sampleCount = rows.size,
                stats = HeartRateStats.of(rows.map { it.bpm }),
                durationSeconds = duration,
            )
        }

        /**
         * bpm-over-time points for the detail chart, in file order. Uses the
         * `elapsed_s` column when every data row has it; otherwise falls back to
         * a 0-based index so a hand-edited file still plots.
         */
        fun parseSeries(csv: String): List<BpmTrackPoint> {
            val rows = rows(csv)
            val useElapsed = rows.isNotEmpty() && rows.all { it.elapsedS != null }
            if (!useElapsed) {
                return rows.mapIndexed { i, r -> BpmTrackPoint(i.toDouble(), r.bpm) }
            }
            val elapsed = rows.map { it.elapsedS!! }
            val gapThreshold = gapThresholdSeconds(elapsed)
            return rows.mapIndexed { i, r ->
                BpmTrackPoint(
                    elapsedSeconds = elapsed[i],
                    bpm = r.bpm,
                    gapBefore = i > 0 && elapsed[i] - elapsed[i - 1] > gapThreshold,
                )
            }
        }

        /**
         * `max(GAP_MIN_SECONDS, GAP_MEDIAN_FACTOR × median sample spacing)` – the
         * median keeps the rule sensible when a strap samples every few seconds
         * rather than once a second.
         */
        private fun gapThresholdSeconds(elapsed: List<Double>): Double {
            val deltas = elapsed.zipWithNext { a, b -> b - a }.filter { it > 0.0 }.sorted()
            if (deltas.isEmpty()) return GAP_MIN_SECONDS
            val median = deltas[deltas.size / 2]
            return maxOf(GAP_MIN_SECONDS, median * GAP_MEDIAN_FACTOR)
        }
    }
}
