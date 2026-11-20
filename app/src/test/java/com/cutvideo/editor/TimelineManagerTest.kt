package com.cutvideo.editor

import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.timeline.TimelineManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the pure-Kotlin timeline maths that the editor, preview and export all share. */
class TimelineManagerTest {

    private fun docWithClip(): Pair<ProjectDoc, String> {
        val doc = TimelineManager.createProject("test")
        val trackId = doc.tracks.first { it.type == TrackType.VIDEO }.id
        val clip = Clip(
            id = "clip-a",
            trackType = TrackType.VIDEO,
            name = "a",
            uri = "content://test/a.mp4",
            startUs = 0L,
            durationUs = 2_000_000L,
            sourceDurationUs = 5_000_000L
        )
        val withClip = TimelineManager.addClipToTrack(doc, trackId, clip)
        return withClip to clip.id
    }

    @Test
    fun `new projects have one track of each type`() {
        val doc = TimelineManager.createProject("test")
        assertEquals(
            setOf(TrackType.VIDEO, TrackType.OVERLAY, TrackType.TEXT, TrackType.AUDIO),
            doc.tracks.map { it.type }.toSet()
        )
        assertEquals(0L, doc.durationUs)
    }

    @Test
    fun `split produces two clips that cover the original span`() {
        val (doc, clipId) = docWithClip()
        val split = TimelineManager.splitClip(doc, clipId, 800_000L)
        val clips = split.tracks.first { it.type == TrackType.VIDEO }.sortedClips
        assertEquals(2, clips.size)
        assertEquals(2_000_000L, clips.sumOf { it.durationUs })
        assertEquals(0L, clips.first().startUs)
        assertEquals(800_000L, clips.last().startUs)
        assertEquals(800_000L, clips.last().sourceInUs)
    }

    @Test
    fun `trim keeps clips above the minimum length`() {
        val (doc, clipId) = docWithClip()
        val trimmed = TimelineManager.trimEnd(doc, clipId, 200_000L)
        val clip = trimmed.clip(clipId)!!
        assertTrue("trim must respect the minimum clip length",
            clip.durationUs >= TimelineManager.MIN_CLIP_US)
        assertTrue(clip.durationUs < 2_000_000L)
    }

    @Test
    fun `duplicate copies the clip`() {
        val (doc, clipId) = docWithClip()
        val duplicated = TimelineManager.duplicateClip(doc, clipId)
        val clips = duplicated.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, clips.size)
        assertEquals(clips[0].uri, clips[1].uri)
        assertTrue(clips[0].id != clips[1].id)
    }

    @Test
    fun `deleting a clip empties its track`() {
        val (doc, clipId) = docWithClip()
        val deleted = TimelineManager.deleteClip(doc, clipId)
        assertEquals(0, deleted.tracks.sumOf { it.clips.size })
    }

    @Test
    fun `snap pulls values near a point and leaves distant values alone`() {
        val points = listOf(1_000_000L, 2_500_000L)
        assertEquals(1_000_000L, TimelineManager.snap(1_050_000L, points))
        assertEquals(3_000_000L, TimelineManager.snap(3_000_000L, points))
    }

    @Test
    fun `frame stepping uses the project frame rate`() {
        val oneFrameAt30 = TimelineManager.stepFrame(1_000_000L, 1, 30, 5_000_000L)
        assertTrue(oneFrameAt30 > 1_000_000L)
        assertTrue(oneFrameAt30 - 1_000_000L in 30_000L..35_000L)
        assertEquals(0L, TimelineManager.stepFrame(10_000L, -1, 30, 5_000_000L))
        assertEquals(5_000_000L, TimelineManager.stepFrame(4_990_000L, 1, 30, 5_000_000L))
    }

    @Test
    fun `closing gaps pulls later clips forward`() {
        val doc = TimelineManager.createProject("test")
        val trackId = doc.tracks.first { it.type == TrackType.VIDEO }.id
        val first = Clip(id = "a", trackType = TrackType.VIDEO, startUs = 0L, durationUs = 1_000_000L)
        val second = Clip(id = "b", trackType = TrackType.VIDEO, startUs = 3_000_000L, durationUs = 1_000_000L)
        var withClips = TimelineManager.addClipToTrack(doc, trackId, first)
        withClips = TimelineManager.addClipToTrack(withClips, trackId, second, atUs = 3_000_000L)
        val closed = TimelineManager.closeVideoGaps(withClips)
        val clips = closed.tracks.first { it.type == TrackType.VIDEO }.sortedClips
        assertEquals(1_000_000L, clips.last().startUs)
        assertNotNull(closed.clip("b"))
    }

    @Test
    fun `source time accounts for speed`() {
        val clip = Clip(
            id = "c",
            trackType = TrackType.VIDEO,
            startUs = 0L,
            durationUs = 1_000_000L,
            sourceInUs = 500_000L,
            speed = 2f
        )
        assertEquals(500_000L, TimelineManager.sourceTimeUs(clip, 0L))
        assertEquals(1_500_000L, TimelineManager.sourceTimeUs(clip, 500_000L))
    }
}
