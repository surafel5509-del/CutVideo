package com.cutvideo.editor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.cutvideo.editor.data.db.MediaKind

data class MediaInfo(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val kind: MediaKind
)

/**
 * Reads real metadata straight from the media container with [MediaMetadataRetriever].
 * Nothing here is guessed: if a file cannot be parsed the import fails with a readable message.
 */
object MediaProbe {

    private const val TAG = "MediaProbe"

    fun probe(context: Context, uri: Uri): Result<MediaInfo> = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri)

        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { displayName = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val swap = width
                width = height
                height = swap
            }
            if (durationMs <= 0L) throw IllegalArgumentException("Media has no readable duration")
            if (!hasVideo && !hasAudio) throw IllegalArgumentException("Unsupported media: no video or audio stream")

            MediaInfo(
                uri = uri,
                displayName = displayName,
                mimeType = mime,
                durationUs = durationMs * 1000L,
                width = width,
                height = height,
                sizeBytes = size,
                hasAudio = hasAudio,
                hasVideo = hasVideo,
                kind = if (hasVideo) MediaKind.VIDEO else MediaKind.AUDIO
            )
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure { Log.w(TAG, "probe failed for $uri", it) }

    private fun guessMime(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    /**
     * Probes a static image (photo or transparent PNG). Images are stills, so [MediaInfo.durationUs]
     * is 0 and the clip layer supplies the on-screen duration.
     */
    fun probeImage(context: Context, uri: Uri): Result<MediaInfo> = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/png"
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { displayName = it }
                }
            }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Unsupported image: ${displayName}")
        }
        MediaInfo(
            uri = uri,
            displayName = displayName,
            mimeType = mime,
            durationUs = 0L,
            width = bounds.outWidth,
            height = bounds.outHeight,
            sizeBytes = 0L,
            hasAudio = false,
            hasVideo = true,
            kind = MediaKind.IMAGE
        )
    }.onFailure { Log.w(TAG, "image probe failed for $uri", it) }
}
