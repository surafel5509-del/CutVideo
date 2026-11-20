package com.cutvideo.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk + memory cache for real video frames.
 *
 * Used for: home-screen project covers, timeline clip filmstrips, media picker tiles and the filter /
 * effect preview cards (which then run the frame through the real colour pipeline).
 */
class ThumbnailStore(context: Context) {

    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, "thumbs").apply { mkdirs() }
    private val waveformRoot: File = File(appContext.filesDir, "waveforms").apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cachedFile(key: String): File? = File(root, "$key.jpg").takeIf { it.exists() && it.length() > 0 }

    suspend fun projectThumbnail(uri: String, timeUs: Long = 0L): Bitmap? = withContext(Dispatchers.IO) {
        frame(uri, timeUs, 480)
    }

    /** Extracts one frame as a Bitmap at up to [maxSize] px on the long edge. */
    fun frame(uriString: String, timeUs: Long, maxSize: Int): Bitmap? {
        val key = cacheKey(uriString, timeUs, maxSize)
        memory.get(key)?.let { return it }
        val file = cachedFile(key)
        if (file != null) {
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                memory.put(key, it)
                return it
            }
        }

        val bitmap = extractFrame(uriString, timeUs, maxSize) ?: return null
        runCatching {
            FileOutputStream(fileFor(key)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }.onFailure { Log.w(TAG, "could not cache thumbnail: ${it.message}") }
        memory.put(key, bitmap)
        return bitmap
    }

    /** Saves a project cover (used for recents). */
    suspend fun saveProjectCover(projectId: String, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(root, "cover_$projectId.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        }.getOrNull()
    }

    /** Filmstrip frames for a timeline clip. Returns frames evenly spaced across the clip. */
    suspend fun filmstrip(uriString: String, count: Int, maxSize: Int = 96): List<Bitmap> =
        withContext(Dispatchers.IO) {
            if (count <= 0) return@withContext emptyList()
            val info = runCatching { probeDuration(uriString) }.getOrDefault(0L)
            if (info <= 0L) return@withContext emptyList()
            (0 until count).mapNotNull { i ->
                val t = (info * (i + 0.5) / count).toLong()
                frame(uriString, t, maxSize)
            }
        }

    fun waveformFile(key: String): File = File(waveformRoot, "$key.wave")

    fun cacheKey(uri: String, timeUs: Long, maxSize: Int): String {
        val raw = "$uri|${timeUs / 1000}|$maxSize"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun clear() {
        memory.evictAll()
        root.listFiles()?.forEach { it.delete() }
        waveformRoot.listFiles()?.forEach { it.delete() }
    }

    fun cacheSizeBytes(): Long =
        (root.listFiles()?.sumOf { it.length() } ?: 0L) + (waveformRoot.listFiles()?.sumOf { it.length() } ?: 0L)

    private fun fileFor(key: String): File = File(root, "$key.jpg")

    private fun extractFrame(uriString: String, timeUs: Long, maxSize: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content" || uri.scheme == "file") {
                retriever.setDataSource(appContext, uri)
            } else {
                retriever.setDataSource(uriString)
            }
            // getScaledFrameAtTime keeps memory bounded for 4K sources.
            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs.coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxSize,
                    maxSize
                )
            } else {
                retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.let { scaleDown(it, maxSize) }
        } catch (t: Throwable) {
            Log.w(TAG, "frame extraction failed for $uriString", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSize) return bitmap
        val scale = maxSize.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun probeDuration(uriString: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(uriString))
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "ThumbnailStore"
    }
}
