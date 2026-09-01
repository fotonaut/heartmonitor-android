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
import java.time.ZoneId

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
    }

    /**
     * Writes [samples] to CSV. Runs on [Dispatchers.IO].
     * @throws IllegalArgumentException if [samples] is empty.
     */
    suspend fun save(samples: List<HeartRateSample>): CsvSaveResult = withContext(Dispatchers.IO) {
        require(samples.isNotEmpty()) { "Keine Messwerte zum Speichern." }

        val zone = ZoneId.systemDefault()
        val startMs = samples.first().timestampMs
        val fileName = HeartRateCsv.fileName(startMs, zone)
        val csv = HeartRateCsv.build(samples, zone, startMs)

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

    /**
     * Deletes a recording. Removes the app-storage copy and, best effort on
     * API 29+, the identically named public copy in `Downloads/HeartMonitor/`.
     * Returns true when the app-storage copy was removed (that is the file the
     * recordings list shows).
     */
    fun delete(file: File): Boolean {
        val appDeleted =
            runCatching { file.parentFile == appDir() && file.delete() }.getOrDefault(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { deleteFromDownloads(file.name) }
                .onSuccess { removed ->
                    if (removed > 0) Log.d(TAG, "Removed public copy of ${file.name}")
                }
                .onFailure { Log.w(TAG, "MediaStore delete failed for ${file.name}", it) }
        }
        return appDeleted
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteFromDownloads(fileName: String): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // MediaStore normalises the RELATIVE_PATH we wrote to a trailing slash.
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBDIR/"
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        return resolver.delete(collection, selection, arrayOf(fileName, relativePath))
    }

    /** Directory that [listRecordings] reads from (also declared in file_paths.xml). */
    fun recordingsDir(): File = appDir()

    // -----------------------------------------------------------------

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
