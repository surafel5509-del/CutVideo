package com.cutvideo.editor.domain.subtitle

import java.util.Locale

data class SubtitleCue(
    val index: Int,
    val startUs: Long,
    val endUs: Long,
    val text: String
)

/**
 * Small, dependency-free SRT reader/writer.
 *
 * Subtitle import creates real text clips on the text track, and export writes the text clips back
 * out — so subtitles are part of the editing document, not a side file.
 */
object SrtCodec {

    private val TIMECODE = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    fun parse(source: String): List<SubtitleCue> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = ArrayList<SubtitleCue>()
        blocks.forEach { block ->
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@forEach
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex < 0) return@forEach
            val timeLine = lines[timeLineIndex]
            val parts = timeLine.split("-->")
            if (parts.size < 2) return@forEach
            val start = parseTimecode(parts[0]) ?: return@forEach
            val end = parseTimecode(parts[1]) ?: return@forEach
            val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
            if (text.isEmpty() || end <= start) return@forEach
            cues += SubtitleCue(cues.size + 1, start, end, text)
        }
        return cues.sortedBy { it.startUs }
    }

    fun write(cues: List<SubtitleCue>): String = buildString {
        cues.sortedBy { it.startUs }.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(format(cue.startUs)).append(" --> ").append(format(cue.endUs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun parseTimecode(raw: String): Long? {
        val match = TIMECODE.find(raw.trim()) ?: return null
        val (h, m, s, ms) = match.destructured
        val millis = ms.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000_000L +
            m.toLong() * 60_000_000L +
            s.toLong() * 1_000_000L +
            millis * 1000L
    }

    fun format(us: Long): String {
        val totalMs = (us / 1000).coerceAtLeast(0)
        val ms = totalMs % 1000
        val sec = (totalMs / 1000) % 60
        val min = (totalMs / 60_000) % 60
        val hour = totalMs / 3_600_000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hour, min, sec, ms)
    }
}
