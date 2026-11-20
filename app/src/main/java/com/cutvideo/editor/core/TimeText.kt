package com.cutvideo.editor.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object TimeText {

    /** 00:12 , 01:02:03 style clock used across the editor. */
    fun clock(us: Long): String {
        val totalMs = (abs(us) / 1000)
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 00:12.340 frame-accurate clock for the transport bar. */
    fun clockWithFrames(us: Long, fps: Int): String {
        val safeUs = if (us < 0) 0 else us
        val frameUs = 1_000_000L / fps.coerceAtLeast(1)
        val frame = (safeUs % 1_000_000L) / frameUs
        return "${clock(safeUs)}:${String.format(Locale.US, "%02d", frame)}"
    }

    fun seconds(us: Long): String = String.format(Locale.US, "%.1fs", us / 1_000_000f)

    fun relativeDate(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMillis
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "Just now"
            diff < hour -> "${diff / minute} min ago"
            diff < day -> "${diff / hour} h ago"
            diff < 2 * day -> "Yesterday"
            diff < 7 * day -> "${diff / day} days ago"
            else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
        }
    }

    fun bytes(size: Long): String = when {
        size <= 0 -> "-"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", size / 1024f)
        size < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / (1024f * 1024f))
        else -> String.format(Locale.US, "%.2f GB", size / (1024f * 1024f * 1024f))
    }
}
