package com.cutvideo.editor.ui.screens.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceMedia(
    val uri: Uri,
    val name: String,
    val durationUs: Long,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val dateAdded: Long,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Queries MediaStore for the clips and audio already on the device.
 *
 * This is the "Media Picker" screen: real thumbnails from the device library, with the document picker
 * as a fallback for cloud/other providers.
 */
class MediaPickerViewModel(private val container: AppContainer) : ViewModel() {

    private val _videos = MutableStateFlow<List<DeviceMedia>>(emptyList())
    val videos: StateFlow<List<DeviceMedia>> = _videos.asStateFlow()

    private val _audio = MutableStateFlow<List<DeviceMedia>>(emptyList())
    val audio: StateFlow<List<DeviceMedia>> = _audio.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    queryVideos() to queryAudio()
                }
            }
            result.onSuccess { (videos, audio) ->
                _videos.value = videos
                _audio.value = audio
                _error.value = if (videos.isEmpty() && audio.isEmpty()) {
                    "No media found on this device. Use “Browse files” to pick from another provider."
                } else null
            }.onFailure {
                _error.value = "Could not read the media library: ${it.message}. Use “Browse files” instead."
            }
            _loading.value = false
        }
    }

    /** Imports the selected device media into the current project. */
    fun importSelection(uris: List<Uri>, isVideo: Boolean, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val type = if (isVideo) {
                com.cutvideo.editor.domain.model.TrackType.VIDEO
            } else {
                com.cutvideo.editor.domain.model.TrackType.AUDIO
            }
            container.editorController.importMedia(uris, type)
            onDone(uris.size)
        }
    }

    private fun queryVideos(): List<DeviceMedia> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val list = ArrayList<DeviceMedia>()
        val resolver = resolver()
        resolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthIndex = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightIndex = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                list += DeviceMedia(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameIndex) ?: "Video",
                    durationUs = cursor.getLong(durationIndex) * 1000L,
                    sizeBytes = cursor.getLong(sizeIndex),
                    isVideo = true,
                    dateAdded = cursor.getLong(dateIndex) * 1000L,
                    width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0,
                    height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
                )
            }
        }
        return list
    }

    private fun queryAudio(): List<DeviceMedia> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val list = ArrayList<DeviceMedia>()
        val resolver = resolver()
        resolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                list += DeviceMedia(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameIndex) ?: "Audio",
                    durationUs = cursor.getLong(durationIndex) * 1000L,
                    sizeBytes = cursor.getLong(sizeIndex),
                    isVideo = false,
                    dateAdded = cursor.getLong(dateIndex) * 1000L
                )
            }
        }
        return list
    }

    private fun resolver() = container.appContext.contentResolver
}
