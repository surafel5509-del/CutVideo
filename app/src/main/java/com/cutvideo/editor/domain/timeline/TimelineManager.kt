package com.cutvideo.editor.domain.timeline

import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationSpec
import com.cutvideo.editor.domain.model.Ids
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.Track
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.model.TransitionSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * All timeline arithmetic lives here as pure functions over [ProjectDoc].
 *
 * Keeping it pure means the editor ViewModel can expose an immutable state stream, undo/redo is a
 * snapshot stack, and the same rules can be unit tested on the JVM without Android. The native
 * engine mirrors the read-only parts of this (`timeline_renderer.cpp`) so a frame's active clips and
 * source times are resolved identically in preview and export.
 */
object TimelineManager {

    const val MIN_CLIP_US: Long = 80_000L
    const val DEFAULT_TEXT_US: Long = 3_000_000L
    const val DEFAULT_IMAGE_US: Long = 4_000_000L
    const val SNAP_THRESHOLD_US: Long = 120_000L

    fun createProject(
        name: String,
        aspect: AspectRatio = AspectRatio.RATIO_16_9,
        frameRate: Int = 30,
        id: String = Ids.project()
    ): ProjectDoc = ProjectDoc(
        id = id,
        name = name,
        aspect = aspect,
        frameRate = frameRate,
        tracks = defaultTracks()
    )

    fun defaultTracks(): List<Track> = listOf(
        Track(Ids.track(), TrackType.VIDEO, "Main video"),
        Track(Ids.track(), TrackType.OVERLAY, "Overlay"),
        Track(Ids.track(), TrackType.TEXT, "Text"),
        Track(Ids.track(), TrackType.AUDIO, "Audio")
    )

    /** Adds a track of [type] and returns the new doc plus the created track id. */
    fun addTrack(doc: ProjectDoc, type: TrackType, name: String? = null): Pair<ProjectDoc, String> {
        val count = doc.tracks.count { it.type == type } + 1
        val track = Track(
            id = Ids.track(),
            type = type,
            name = name ?: "${type.name.lowercase().replaceFirstChar { it.uppercase() }} $count"
        )
        return doc.copy(tracks = doc.tracks + track, updatedAt = System.currentTimeMillis()) to track.id
    }

    fun removeTrack(doc: ProjectDoc, trackId: String): ProjectDoc =
        doc.copy(tracks = doc.tracks.filterNot { it.id == trackId })

    // ---------------------------------------------------------------------------------------------
    // Clip lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Appends a clip to the track matching its type. If a specific [atUs] is supplied the clip is
     * pushed to the first free slot at or after that time so clips never overlap on video tracks.
     */
    fun addClip(doc: ProjectDoc, clip: Clip, atUs: Long? = null): ProjectDoc {
        val track = doc.tracks.firstOrNull { it.type == clip.trackType }
            ?: return doc
        val start = atUs?.let { firstFreeStart(track, it, clip.durationUs) }
            ?: max(track.endUs, 0L)
        val placed = clip.copy(startUs = start)
        return doc.copy(
            tracks = doc.tracks.map { if (it.id == track.id) it.copy(clips = it.clips + placed) else it },
            updatedAt = System.currentTimeMillis()
        )
    }

    fun addClipToTrack(doc: ProjectDoc, trackId: String, clip: Clip, atUs: Long? = null): ProjectDoc {
        val track = doc.track(trackId) ?: return doc
        val start = atUs?.let { firstFreeStart(track, it, clip.durationUs) } ?: track.endUs
        val placed = clip.copy(startUs = start)
        return doc.copy(
            tracks = doc.tracks.map { if (it.id == trackId) it.copy(clips = it.clips + placed) else it },
            updatedAt = System.currentTimeMillis()
        )
    }

    fun updateClip(doc: ProjectDoc, clipId: String, transform: (Clip) -> Clip): ProjectDoc {
        var changed = false
        val tracks = doc.tracks.map { track ->
            if (track.clips.none { it.id == clipId }) track else {
                changed = true
                track.copy(clips = track.clips.map { if (it.id == clipId) transform(it) else it })
            }
        }
        if (!changed) return doc
        return doc.copy(tracks = tracks, updatedAt = System.currentTimeMillis())
    }

    fun deleteClip(doc: ProjectDoc, clipId: String): ProjectDoc = doc.copy(
        tracks = doc.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) },
        updatedAt = System.currentTimeMillis()
    )

    /** Duplicates a clip and inserts the copy in the first free slot after the original. */
    fun duplicateClip(doc: ProjectDoc, clipId: String): ProjectDoc {
        val track = doc.trackOf(clipId) ?: return doc
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return doc
        val copy = clip.copy(id = Ids.clip(), startUs = firstFreeStart(track, clip.endUs, clip.durationUs))
        return doc.copy(
            tracks = doc.tracks.map { if (it.id == track.id) it.copy(clips = it.clips + copy) else it },
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Splits [clipId] at absolute timeline position [atUs]. The right-hand part keeps the media
     * offset so playback is frame accurate, and text clips share their style.
     */
    fun splitClip(doc: ProjectDoc, clipId: String, atUs: Long): ProjectDoc {
        val track = doc.trackOf(clipId) ?: return doc
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return doc
        val splitAt = atUs.coerceIn(clip.startUs + MIN_CLIP_US, clip.endUs - MIN_CLIP_US)
        if (clip.durationUs < MIN_CLIP_US * 2) return doc

        val leftDuration = splitAt - clip.startUs
        val left = clip.copy(
            durationUs = leftDuration,
            fadeOutUs = min(clip.fadeOutUs, leftDuration / 2),
            transitionOut = null,
            animationOut = ClipAnimationSpec()
        )
        val right = clip.copy(
            id = Ids.clip(),
            startUs = splitAt,
            durationUs = clip.endUs - splitAt,
            sourceInUs = clip.sourceInUs + (leftDuration * clip.speed).roundToLong(),
            fadeInUs = min(clip.fadeInUs, (clip.endUs - splitAt) / 2),
            transitionIn = null,
            animationIn = ClipAnimationSpec()
        )
        return doc.copy(
            tracks = doc.tracks.map { t ->
                if (t.id != track.id) t
                else t.copy(clips = t.clips.filterNot { it.id == clipId } + left + right)
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Trims the head of a clip. Moving the head right consumes source material (sourceIn grows),
     * moving it left restores it, clamped by available source and the previous clip on the track.
     */
    fun trimStart(doc: ProjectDoc, clipId: String, newStartUs: Long): ProjectDoc {
        val track = doc.trackOf(clipId) ?: return doc
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return doc
        val prevEnd = track.clips.filter { it.id != clipId && it.startUs <= clip.startUs }
            .maxOfOrNull { it.endUs } ?: 0L
        val minStart = max(prevEnd, clip.startUs - (clip.sourceInUs / clip.speed).roundToLong())
        val maxStart = clip.endUs - MIN_CLIP_US
        val start = newStartUs.coerceIn(minStart, maxStart)
        val delta = start - clip.startUs
        if (delta == 0L) return doc
        val newSourceIn = (clip.sourceInUs + (delta * clip.speed).roundToLong()).coerceAtLeast(0L)
        return updateClip(doc, clipId) {
            it.copy(
                startUs = start,
                sourceInUs = newSourceIn,
                durationUs = clip.durationUs - delta,
                fadeInUs = min(it.fadeInUs, (clip.durationUs - delta) / 2)
            )
        }
    }

    /** Trims the tail. The clip cannot extend past its available source material. */
    fun trimEnd(doc: ProjectDoc, clipId: String, newEndUs: Long): ProjectDoc {
        val track = doc.trackOf(clipId) ?: return doc
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return doc
        val nextStart = track.clips.filter { it.id != clipId && it.startUs >= clip.endUs }
            .minOfOrNull { it.startUs } ?: Long.MAX_VALUE
        val sourceAvailable = if (clip.sourceDurationUs > 0)
            ((clip.sourceDurationUs - clip.sourceInUs) / clip.speed).roundToLong()
        else Long.MAX_VALUE
        val maxEnd = min(nextStart, clip.startUs + sourceAvailable)
        val minEnd = clip.startUs + MIN_CLIP_US
        val end = newEndUs.coerceIn(minEnd, max(maxEnd, minEnd))
        val duration = end - clip.startUs
        if (duration == clip.durationUs) return doc
        return updateClip(doc, clipId) {
            it.copy(durationUs = duration, fadeOutUs = min(it.fadeOutUs, duration / 2))
        }
    }

    /** Moves a clip inside the timeline, optionally to another track of the same type. */
    fun moveClip(doc: ProjectDoc, clipId: String, newStartUs: Long, targetTrackId: String? = null): ProjectDoc {
        val sourceTrack = doc.trackOf(clipId) ?: return doc
        val clip = sourceTrack.clips.firstOrNull { it.id == clipId } ?: return doc
        val destTrack = targetTrackId?.let { doc.track(it) }?.takeIf { it.type == clip.trackType } ?: sourceTrack
        val others = destTrack.clips.filter { it.id != clipId }
        val prevEnd = others.filter { it.startUs <= newStartUs }.maxOfOrNull { it.endUs } ?: 0L
        val nextStart = others.filter { it.startUs > newStartUs }.minOfOrNull { it.startUs } ?: Long.MAX_VALUE
        val minStart = max(0L, prevEnd)
        val maxStart = if (nextStart == Long.MAX_VALUE) Long.MAX_VALUE else max(minStart, nextStart - clip.durationUs)
        val start = newStartUs.coerceIn(minStart, maxStart)
        val moved = clip.copy(startUs = start)

        val tracks = doc.tracks.map { t ->
            when {
                t.id == destTrack.id && t.id == sourceTrack.id -> t.copy(clips = others + moved)
                t.id == destTrack.id -> t.copy(clips = t.clips + moved)
                t.id == sourceTrack.id -> t.copy(clips = t.clips.filterNot { it.id == clipId })
                else -> t
            }
        }
        return doc.copy(tracks = tracks, updatedAt = System.currentTimeMillis())
    }

    /** Changes playback rate and keeps the preview/export durations in sync. */
    fun setSpeed(doc: ProjectDoc, clipId: String, speed: Float): ProjectDoc = updateClip(doc, clipId) { clip ->
        val clamped = speed.coerceIn(0.25f, 4f)
        val sourceSpan = min(
            clip.sourceSpanUs,
            if (clip.sourceDurationUs > 0) clip.sourceDurationUs - clip.sourceInUs else clip.sourceSpanUs
        )
        clip.copy(speed = clamped, durationUs = (sourceSpan / clamped).roundToLong().coerceAtLeast(MIN_CLIP_US))
    }

    /** 0..1 normalised volume. */
    fun setVolume(doc: ProjectDoc, clipId: String, volume: Float): ProjectDoc =
        updateClip(doc, clipId) { it.copy(volume = volume.coerceIn(0f, 2f)) }

    fun setMuted(doc: ProjectDoc, clipId: String, muted: Boolean): ProjectDoc =
        updateClip(doc, clipId) { it.copy(muted = muted) }

    fun setFades(doc: ProjectDoc, clipId: String, fadeInUs: Long, fadeOutUs: Long): ProjectDoc =
        updateClip(doc, clipId) { clip ->
            clip.copy(
                fadeInUs = fadeInUs.coerceIn(0, clip.durationUs / 2),
                fadeOutUs = fadeOutUs.coerceIn(0, clip.durationUs / 2)
            )
        }

    fun setTrackVolume(doc: ProjectDoc, trackId: String, volume: Float): ProjectDoc = doc.copy(
        tracks = doc.tracks.map { if (it.id == trackId) it.copy(volume = volume.coerceIn(0f, 2f)) else it }
    )

    fun setTrackMuted(doc: ProjectDoc, trackId: String, muted: Boolean): ProjectDoc = doc.copy(
        tracks = doc.tracks.map { if (it.id == trackId) it.copy(muted = muted) else it }
    )

    /** Sets the transition on the boundary between two adjacent clips. */
    fun setBoundaryTransition(doc: ProjectDoc, fromClipId: String, toClipId: String, spec: TransitionSpec?): ProjectDoc {
        val withOut = updateClip(doc, fromClipId) { clip -> clip.copy(transitionOut = spec) }
        return updateClip(withOut, toClipId) { clip -> clip.copy(transitionIn = spec) }
    }

    // ---------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------

    fun projectDurationUs(doc: ProjectDoc): Long = doc.tracks.flatMap { it.clips }.maxOfOrNull { it.endUs } ?: 0L

    fun clipsAt(doc: ProjectDoc, type: TrackType, timeUs: Long): List<Clip> =
        doc.tracks.filter { it.type == type && !it.hidden }
            .flatMap { track -> track.clips.filter { timeUs >= it.startUs && timeUs < it.endUs } }

    fun primaryVideoClip(doc: ProjectDoc, timeUs: Long): Clip? =
        clipsAt(doc, TrackType.VIDEO, timeUs).firstOrNull()

    /** Maps a timeline position onto source media time for a clip, honouring speed and reverse. */
    fun sourceTimeUs(clip: Clip, timelineUs: Long): Long {
        val local = (timelineUs - clip.startUs).coerceIn(0L, clip.durationUs)
        val consumed = (local * clip.speed).roundToLong()
        return if (clip.reversed) {
            (clip.sourceInUs + (clip.sourceSpanUs - consumed)).coerceAtLeast(0L)
        } else {
            clip.sourceInUs + consumed
        }
    }

    /** All edit points a dragging clip can snap to: clip edges, playhead, zero. */
    fun snapPoints(doc: ProjectDoc, excludeClipId: String? = null, playheadUs: Long? = null): List<Long> {
        val points = mutableListOf(0L)
        doc.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (clip.id != excludeClipId) {
                    points += clip.startUs
                    points += clip.endUs
                }
            }
        }
        playheadUs?.let { points += it }
        return points.distinct().sorted()
    }

    /** Snaps a clip edge if it lands within [thresholdUs] of any snap point. */
    fun snap(value: Long, points: List<Long>, thresholdUs: Long = SNAP_THRESHOLD_US): Long {
        var best = value
        var bestDistance = Long.MAX_VALUE
        points.forEach { p ->
            val d = abs(p - value)
            if (d < bestDistance && d <= thresholdUs) {
                bestDistance = d
                best = p
            }
        }
        return best
    }

    /** Snaps either the head or the tail of a clip, whichever is closer. */
    fun snapClipStart(startUs: Long, durationUs: Long, points: List<Long>, thresholdUs: Long = SNAP_THRESHOLD_US): Long {
        val headSnap = snap(startUs, points, thresholdUs)
        val tailSnap = snap(startUs + durationUs, points, thresholdUs) - durationUs
        val headDelta = abs(headSnap - startUs)
        val tailDelta = abs(tailSnap - startUs)
        return if (tailDelta < headDelta) max(0L, tailSnap) else max(0L, headSnap)
    }

    /** Frame stepping used by the transport bar. */
    fun stepFrame(timeUs: Long, frames: Int, frameRate: Int, durationUs: Long): Long {
        val frameUs = 1_000_000L / frameRate.coerceAtLeast(1)
        return (timeUs + frames * frameUs).coerceIn(0L, max(0L, durationUs))
    }

    /** First position >= [from] where a clip of [length] fits on [track]. */
    fun firstFreeStart(track: Track, from: Long, length: Long): Long {
        var candidate = max(0L, from)
        track.sortedClips.forEach { existing ->
            if (candidate + length <= existing.startUs) return candidate
            if (candidate < existing.endUs) candidate = existing.endUs
        }
        return candidate
    }

    /**
     * Collapses gaps on the primary video track ("magnetic" main track).
     *
     * The preview is a playlist and the export is a Media3 sequence: both play back-to-back, so the
     * main video track is deliberately kept gapless. Trimmed head gaps are closed by shifting the
     * following clips left. Audio/text/overlay tracks stay absolute so they can be offset freely.
     */
    fun closeVideoGaps(doc: ProjectDoc): ProjectDoc {
        val videoTrack = doc.tracks.firstOrNull { it.type == TrackType.VIDEO } ?: return doc
        val sorted = videoTrack.sortedClips
        var cursor = 0L
        var changed = false
        val normalized = sorted.map { clip ->
            if (clip.startUs != cursor) {
                changed = true
                clip.copy(startUs = cursor).also { cursor += it.durationUs }
            } else {
                cursor += clip.durationUs
                clip
            }
        }
        if (!changed) return doc
        return doc.copy(
            tracks = doc.tracks.map { if (it.id == videoTrack.id) it.copy(clips = normalized) else it },
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Total number of clips on the video track. */
    fun videoClipCount(doc: ProjectDoc): Int =
        doc.tracks.firstOrNull { it.type == TrackType.VIDEO }?.clips?.size ?: 0

    /** Splits a text/audio/video clip at the playhead - convenience used by the toolbar. */
    fun splitAtPlayhead(doc: ProjectDoc, timeUs: Long, clipId: String? = null): ProjectDoc {
        val target = clipId ?: doc.tracks
            .filter { !it.locked }
            .flatMap { it.clips }
            .filter { timeUs > it.startUs + MIN_CLIP_US && timeUs < it.endUs - MIN_CLIP_US }
            .minByOrNull { abs(it.startUs - timeUs) }
            ?.id
            ?: return doc
        return splitClip(doc, target, timeUs)
    }
}
