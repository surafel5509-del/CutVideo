package com.cutvideo.editor.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedVideo(val uri: Uri, val displayPath: String, val sizeBytes: Long)

/**
 * Publishes a finished export into the device gallery through MediaStore.
 *
 * Uses the IS_PENDING handshake so the file is never visible half-written, and works without any
 * storage permission on Android 10+ (legacy path is only used on API 26-28 where WRITE_EXTERNAL_STORAGE
 * is declared with maxSdkVersion=28).
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"
    private const val ALBUM = "CutVideo"

    suspend fun publish(context: Context, file: File, projectName: String): SavedVideo? =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) return@withContext null
            val name = buildFileName(projectName)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM)
                    dir.mkdirs()
                    put(MediaStore.Video.Media.DATA, File(dir, name).absolutePath)
                }
            }

            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: run {
                Log.e(TAG, "could not create MediaStore entry")
                return@withContext null
            }

            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output, 1 shl 16) }
                } ?: error("no output stream")
            }.isSuccess

            if (!copied) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }

            SavedVideo(uri, "$ALBUM/$name", file.length())
        }

    /** Deletes a previously published video from the gallery. */
    suspend fun delete(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    fun buildFileName(projectName: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = projectName.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_')
            .ifBlank { "CutVideo" }.take(32)
        return "CutVideo_${safe}_$stamp.mp4"
    }
}
