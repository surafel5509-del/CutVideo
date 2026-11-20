package com.cutvideo.editor

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.export.ExportService
import com.cutvideo.editor.nativebridge.NativeLib

class CutVideoApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ExportService.createChannel(this)
        // Touch the native library early so diagnostics and the first render are warm.
        NativeLib.version()
    }

    /**
     * Coil loader used for every timeline filmstrip and media-picker thumbnail. The video frame decoder
     * is what makes `AsyncImage(model = <video uri>)` show a real frame instead of an icon.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
