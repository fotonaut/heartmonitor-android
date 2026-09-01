package de.hstmstr.heartmonitor.data

import de.hstmstr.heartmonitor.recording.HeartRateStats

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
        private const val COL_BPM = 3

        /**
         * Parses the text of a [HeartRateCsv] document. The header line and any
         * blank or too-short line are skipped, so a truncated file still yields
         * a best-effort summary.
         */
        fun parse(csv: String): HeartRateCsvSummary {
            val bpms = ArrayList<Int>()
            var firstEpoch: Long? = null
            var lastEpoch: Long? = null

            for (line in csv.lineSequence()) {
                if (line.isBlank()) continue
                val cols = line.split(',')
                if (cols.size <= COL_BPM) continue
                // Non-numeric bpm (the "bpm" header cell, a corrupt row) -> skip.
                val bpm = cols[COL_BPM].trim().toIntOrNull() ?: continue
                bpms.add(bpm)
                cols[COL_EPOCH_MS].trim().toLongOrNull()?.let { epoch ->
                    if (firstEpoch == null) firstEpoch = epoch
                    lastEpoch = epoch
                }
            }

            val duration = firstEpoch?.let { first ->
                lastEpoch?.takeIf { it != first }?.let { (it - first) / 1000.0 }
            }

            return HeartRateCsvSummary(
                sampleCount = bpms.size,
                stats = HeartRateStats.of(bpms),
                durationSeconds = duration,
            )
        }
    }
}
