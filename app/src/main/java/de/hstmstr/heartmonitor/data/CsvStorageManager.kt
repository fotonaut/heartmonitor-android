package de.hstmstr.heartmonitor.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import de.hstmstr.heartmonitor.ble.HeartRateSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where a recording ended up on disk. */
data class CsvSaveResult(
    val fileName: String,
    /** Absolute path inside the app-private external files dir (always set). */
    val appStoragePath: String,
    /** Human-readable public location (Downloads/…), or null when not exported. */
    val publicLocation: String?,
    val sampleCount: Int,
) {
    /** Best location to show the user. */
    val displayLocation: String get() = publicLocation ?: appStoragePath
}

/**
 * Serialises collected [HeartRateSample]s to a timestamped CSV file.
 *
 * Primary copy: app-private storage (`Android/data/<pkg>/files/recordings/`) –
 * needs no permission and works on every API level.
 *
 * Secondary copy (best effort, API 29+): `Downloads/HeartMonitor/` via the
 * MediaStore API, so the file is easy to find in a file manager without
 * `WRITE_EXTERNAL_STORAGE`.
 */
class CsvStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "CsvStorageManager"
        private const val APP_SUBDIR = "recordings"
        private const val PUBLIC_SUBDIR = "HeartMonitor"
        private const val CSV_HEADER =
            "timestamp_iso,timestamp_epoch_ms,elapsed_s,bpm,sensor_contact,rr_ms"

        private val FILE_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
        private val ROW_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }

    /**
     * Writes [samples] to CSV. Runs on [Dispatchers.IO].
     * @throws IllegalArgumentException if [samples] is empty.
     */
    suspend fun save(samples: List<HeartRateSample>): CsvSaveResult = withContext(Dispatchers.IO) {
        require(samples.isNotEmpty()) { "Keine Messwerte zum Speichern." }

        val zone = ZoneId.systemDefault()
        val startMs = samples.first().timestampMs
        val fileName = "hr_" +
            Instant.ofEpochMilli(startMs).atZone(zone).format(FILE_TIMESTAMP) + ".csv"
        val csv = buildCsv(samples, zone, startMs)

        val appFile = writeToAppStorage(fileName, csv)

        val publicLocation = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(fileName, csv)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore export failed, app copy still saved", e)
            null
        }

        CsvSaveResult(
            fileName = fileName,
            appStoragePath = appFile.absolutePath,
            publicLocation = publicLocation,
            sampleCount = samples.size,
        )
    }

    /** CSV files previously written to app storage, newest first. */
    fun listRecordings(): List<File> =
        appDir().listFiles { f -> f.isFile && f.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Deletes a recording from app storage. Returns true on success. */
    fun delete(file: File): Boolean =
        runCatching { file.parentFile == appDir() && file.delete() }.getOrDefault(false)

    /** Directory that [listRecordings] reads from (also declared in file_paths.xml). */
    fun recordingsDir(): File = appDir()

    // -----------------------------------------------------------------

    private fun buildCsv(samples: List<HeartRateSample>, zone: ZoneId, startMs: Long): String {
        val sb = StringBuilder(CSV_HEADER.length + samples.size * 48)
        sb.append(CSV_HEADER).append('\n')
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

    private fun appDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, APP_SUBDIR).apply { mkdirs() }

    private fun writeToAppStorage(fileName: String, csv: String): File {
        val file = File(appDir(), fileName)
        file.writeText(csv, Charsets.UTF_8)
        return file
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(fileName: String, csv: String): String? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBDIR",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, pending) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Downloads/$PUBLIC_SUBDIR/$fileName"
    }
}
