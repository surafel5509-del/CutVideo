package com.cutvideo.editor.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.cutvideo.editor.data.db.MediaAssetEntity
import com.cutvideo.editor.data.db.MediaKind
import com.cutvideo.editor.data.repository.MediaRepository
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.Ids
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.nativebridge.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedMedia(val asset: MediaAssetEntity, val clip: Clip)

/** Fills the Revert tool's import-time snapshot the first time a clip is created. */
internal fun Clip.withOriginal(): Clip = if (original != null) this else copy(original = com.cutvideo.editor.domain.model.ClipOriginal.of(this))

/**
 * Turns user-picked URIs into persisted media assets and real timeline clips.
 *
 * Handles:
 *  - persisting SAF read permission so a project still opens after a reboot,
 *  - probing duration/size/stream layout,
 *  - building a clip with correct source bounds,
 *  - generating the project cover frame and the waveform for audio clips.
 */
class MediaImporter(
    private val context: Context,
    private val mediaRepository: MediaRepository,
    private val thumbnailStore: ThumbnailStore,
    private val nativeEngine: NativeEngine
) {

    /** Grants persisted read access for the URI when the source supports it. */
    fun persistPermission(uri: Uri) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure {
            // Not an error: some providers (e.g. Google Photos) do not persist, they stream instead.
            Log.d(TAG, "URI permission not persistable: ${it.message}")
        }
    }

    suspend fun import(
        uris: List<Uri>,
        projectId: String,
        trackType: TrackType,
        timelineStartUs: Long = 0L
    ): List<ImportedMedia> = withContext(Dispatchers.IO) {
        val results = ArrayList<ImportedMedia>()
        var cursorUs = timelineStartUs
        uris.forEach { uri ->
            persistPermission(uri)
            val info = MediaProbe.probe(context, uri).getOrElse { error ->
                Log.w(TAG, "skipping unreadable media $uri", error)
                return@forEach
            }
            val isAudioTrack = trackType == TrackType.AUDIO
            if (isAudioTrack && !info.hasAudio) return@forEach
            if (!isAudioTrack && !info.hasVideo) return@forEach

            val assetId = Ids.media()
            val clip = Clip(
                id = Ids.clip(),
                trackType = trackType,
                name = info.displayName,
                mediaId = assetId,
                uri = info.uri.toString(),
                mimeType = info.mimeType,
                startUs = cursorUs,
                durationUs = info.durationUs,
                sourceInUs = 0L,
                sourceDurationUs = info.durationUs,
                muted = false,
                volume = 1f
            ).withOriginal()
            val asset = MediaAssetEntity(
                id = assetId,
                projectId = projectId,
                uri = info.uri.toString(),
                displayName = info.displayName,
                mimeType = info.mimeType,
                kind = info.kind.name,
                durationUs = info.durationUs,
                width = info.width,
                height = info.height,
                sizeBytes = info.sizeBytes,
                hasAudio = info.hasAudio,
                waveformPath = null,
                addedAt = System.currentTimeMillis()
            )
            mediaRepository.add(asset)

            if (asset.kind == MediaKind.AUDIO.name || (info.hasAudio && isAudioTrack)) {
                ensureWaveform(asset.id, info.uri)
            }
            cursorUs += clip.durationUs
            results += ImportedMedia(asset, clip)
        }
        results
    }

    /**
     * Imports a static image (photo or transparent PNG) as an overlay layer. Images are stills:
     * the clip keeps a visible duration on the timeline and renders as a real layer in the
     * preview. Compositing image overlays into the exported file follows the app's documented
     * PiP/overlay limitation.
     */
    suspend fun importImage(
        uri: Uri,
        projectId: String,
        insertAtUs: Long = 0L,
        durationUs: Long = 5_000_000L
    ): ImportedMedia? = withContext(Dispatchers.IO) {
        persistPermission(uri)
        val info = MediaProbe.probeImage(context, uri).getOrNull() ?: return@withContext null
        val assetId = Ids.media()
        val clipDurationUs = durationUs.coerceAtLeast(250_000L)
        val clip = Clip(
            id = Ids.clip(),
            trackType = TrackType.OVERLAY,
            name = info.displayName,
            mediaId = assetId,
            uri = info.uri.toString(),
            mimeType = info.mimeType,
            startUs = insertAtUs.coerceAtLeast(0L),
            durationUs = clipDurationUs,
            sourceInUs = 0L,
            sourceDurationUs = clipDurationUs,
            isImage = true
        ).withOriginal()
        val asset = MediaAssetEntity(
            id = assetId,
            projectId = projectId,
            uri = info.uri.toString(),
            displayName = info.displayName,
            mimeType = info.mimeType,
            kind = MediaKind.IMAGE.name,
            durationUs = 0L,
            width = info.width,
            height = info.height,
            sizeBytes = info.sizeBytes,
            hasAudio = false,
            waveformPath = null,
            addedAt = System.currentTimeMillis()
        )
        mediaRepository.add(asset)
        ImportedMedia(asset, clip)
    }

    /** Extracts audio from a video clip into its own audio-track clip. */
    suspend fun extractAudioClip(videoClip: Clip, projectId: String): ImportedMedia? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(videoClip.uri)
            val info = MediaProbe.probe(context, uri).getOrNull() ?: return@withContext null
            if (!info.hasAudio) return@withContext null
            val assetId = Ids.media()
            val clip = Clip(
                id = Ids.clip(),
                trackType = TrackType.AUDIO,
                name = "${videoClip.name} (audio)",
                mediaId = assetId,
                uri = videoClip.uri,
                mimeType = info.mimeType,
                startUs = videoClip.startUs,
                durationUs = videoClip.durationUs,
                sourceInUs = videoClip.sourceInUs,
                sourceDurationUs = info.durationUs
            )
            val asset = MediaAssetEntity(
                id = assetId,
                projectId = projectId,
                uri = videoClip.uri,
                displayName = clip.name,
                mimeType = info.mimeType,
                kind = MediaKind.AUDIO.name,
                durationUs = info.durationUs,
                width = info.width,
                height = info.height,
                sizeBytes = info.sizeBytes,
                hasAudio = true,
                waveformPath = null,
                addedAt = System.currentTimeMillis()
            )
            mediaRepository.add(asset)
            ensureWaveform(assetId, uri)
            ImportedMedia(asset, clip)
        }

    /** Decodes the audio track, computes peaks natively and caches them for the timeline. */
    suspend fun ensureWaveform(assetId: String, uri: Uri): FloatArray? = withContext(Dispatchers.IO) {
        // Waveforms are a convenience: a decode failure (corrupt file, unsupported codec, memory
        // pressure) must never abort the import, so every step is guarded.
        val decoded = runCatching { PcmDecoder.decode(context, uri) }.getOrNull()
            ?: return@withContext null
        val buckets = 320
        val peaks = runCatching { nativeEngine.waveform(decoded.samples, decoded.channels, buckets) }
            .getOrNull()
            ?: fallbackWaveform(decoded.samples, decoded.channels, buckets)
        if (peaks.isEmpty()) return@withContext null
        val file = thumbnailStore.waveformFile(assetId)
        runCatching {
            file.outputStream().use { out ->
                java.nio.ByteBuffer.allocate(peaks.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                    peaks.forEach { putFloat(it) }
                    rewind()
                }.let { buffer ->
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    out.write(bytes)
                }
            }
            mediaRepository.setWaveform(assetId, file.absolutePath)
        }
        peaks
    }

    /** Kotlin fallback for peak extraction if the native library is unavailable. */
    private fun fallbackWaveform(samples: ShortArray, channels: Int, buckets: Int): FloatArray {
        if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
        val frames = samples.size / channels.coerceAtLeast(1)
        val perBucket = (frames / buckets).coerceAtLeast(1)
        return FloatArray(buckets) { b ->
            var peak = 0f
            val start = b * perBucket
            val end = minOf(frames, start + perBucket)
            for (f in start until end) {
                for (c in 0 until channels) {
                    val v = kotlin.math.abs(samples[f * channels + c] / 32768f)
                    if (v > peak) peak = v
                }
            }
            peak
        }
    }

    private companion object {
        const val TAG = "MediaImporter"
    }
}
