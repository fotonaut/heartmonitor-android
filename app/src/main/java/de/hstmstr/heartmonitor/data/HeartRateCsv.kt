package de.hstmstr.heartmonitor.data

import de.hstmstr.heartmonitor.ble.HeartRateSample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure CSV formatting for heart-rate recordings – no Android, no I/O – so the
 * exact byte layout can be unit-tested. [CsvStorageManager] handles the actual
 * file and MediaStore writes.
 *
 * Format (one header line, then one line per sample):
 * ```
 * timestamp_iso,timestamp_epoch_ms,elapsed_s,bpm,sensor_contact,rr_ms
 * ```
 * - `timestamp_iso`   ISO-8601 local time with offset, milliseconds
 * - `elapsed_s`       seconds since the first sample, 2 decimals, `Locale.US`
 * - `sensor_contact`  `true` / `false` / empty (empty = not reported by strap)
 * - `rr_ms`           space-separated RR intervals in ms (may be empty)
 */
object HeartRateCsv {

    const val HEADER = "timestamp_iso,timestamp_epoch_ms,elapsed_s,bpm,sensor_contact,rr_ms"

    private val FILE_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val ROW_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    /** File name for a recording that started at [startMs]: `hr_<timestamp>.csv`. */
    fun fileName(startMs: Long, zone: ZoneId): String =
        "hr_" + Instant.ofEpochMilli(startMs).atZone(zone).format(FILE_TIMESTAMP) + ".csv"

    /**
     * Renders [samples] to the full CSV text. [startMs] is the reference for the
     * `elapsed_s` column (normally the first sample's timestamp).
     */
    fun build(samples: List<HeartRateSample>, zone: ZoneId, startMs: Long): String {
        val sb = StringBuilder(HEADER.length + samples.size * 48)
        sb.append(HEADER).append('\n')
        for (s in samples) {
            val iso = Instant.ofEpochMilli(s.timestampMs).atZone(zone).format(ROW_TIMESTAMP)
            val elapsed = (s.timestampMs - startMs) / 1000.0
            val contact = s.sensorContact?.toString() ?: ""
            val rr = s.rrIntervalsMs.joinToString(separator = " ")
            sb.append(iso).append(',')
                .append(s.timestampMs).append(',')
                .append(String.format(Locale.US, "%.2f", elapsed)).append(',')
                .append(s.bpm).append(',')
                .append(contact).append(',')
                .append(rr).append('\n')
        }
        return sb.toString()
    }
}
