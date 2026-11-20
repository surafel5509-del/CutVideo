package com.cutvideo.editor.nativebridge

import android.util.Log

/**
 * Loads `libcutvideo.so` once and remembers whether it succeeded.
 *
 * The app is designed to stay usable without the native library (for example on an ABI where the
 * build was stripped): every native entry point has a Kotlin fallback, and Settings > Diagnostics
 * reports which path is active instead of silently pretending.
 */
object NativeLib {

    private const val TAG = "NativeLib"

    val isLoaded: Boolean = try {
        System.loadLibrary("cutvideo")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Unable to load libcutvideo.so — falling back to the Kotlin implementations", t)
        false
    }

    val statusText: String
        get() = if (isLoaded) "Native engine loaded" else "Native engine unavailable (Kotlin fallback)"

    fun version(): String = if (isLoaded) {
        runCatching { NativeBridge.nativeVersion() }.getOrDefault("unknown")
    } else {
        "native library not loaded"
    }
}
