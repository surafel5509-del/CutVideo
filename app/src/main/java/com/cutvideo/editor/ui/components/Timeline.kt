package com.cutvideo.editor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.Track
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.timeline.TimelineManager
import com.cutvideo.editor.ui.theme.CutColors
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.roundToLong

/**
 * Real multi-track timeline driven entirely by the editing document.
 *
 * Interactions (all mutate the document through the callbacks):
 *  - tap clip → select + move the playhead to the clip
 *  - long-press clip → clip menu (split / duplicate / delete / properties)
 *  - drag clip body → move, with snapping to other clip edges and the playhead
 *  - drag the left/right tab → trim in/out live
 *  - tap or drag the ruler → scrub the playhead
 *  - pinch the ruler → zoom
 */
@Composable
fun EditorTimeline(
    doc: ProjectDoc,
    playheadUs: Long,
    selectedClipId: String?,
    zoom: TimelineZoom,
    snapping: Boolean,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
    onSelect: (String?) -> Unit,
    onMoveClip: (clipId: String, startUs: Long, trackId: String?, live: Boolean) -> Unit,
    onTrimStart: (clipId: String, startUs: Long, live: Boolean) -> Unit,
    onTrimEnd: (clipId: String, endUs: Long, live: Boolean) -> Unit,
    onGestureFinished: () -> Unit,
    onClipMenu: (Clip) -> Unit,
    onZoomChange: (Float) -> Unit
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val pxPerSecond = zoom.pxPerSecond
    val labelColumnWidth = 60.dp
    val contentWidth: Dp = with(density) {
        val seconds = doc.durationUs.coerceAtLeast(2_000_000L) / 1_000_000f
        ((seconds * pxPerSecond) + 220f).dp
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(CutColors.BackgroundElevated)
            .border(1.dp, CutColors.Border, RoundedCornerShape(22.dp))
    ) {
        // Ruler (tap to seek, drag to scrub, pinch to zoom)
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Box(Modifier.width(labelColumnWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("TIMELINE", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(pxPerSecond) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            if (abs(zoomChange - 1f) > 0.02f) onZoomChange(zoomChange)
                            else if (abs(pan.x) > 2f) Unit
                        }
                    }
                    .pointerInput(pxPerSecond) {
                        detectTapGestures { offset ->
                            val us = ((scrollState.value + offset.x) / pxPerSecond * 1_000_000f).toLong()
                            onSeek(us.coerceAtLeast(0L))
                        }
                    }
                    .pointerInput(pxPerSecond) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val us = ((scrollState.value + change.position.x) / pxPerSecond * 1_000_000f).toLong()
                            onSeek(us.coerceAtLeast(0L))
                        }
                    }
            ) {
                val scroll = scrollState.value
                Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                    drawRuler(pxPerSecond, doc.durationUs)
                }
                RulerLabels(pxPerSecond, doc.durationUs, scroll.toFloat(), Modifier.fillMaxWidth().fillMaxHeight())
            }
        }

        // Tracks
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(labelColumnWidth)) {
                doc.tracks.forEach { track -> TrackLabel(track) }
            }
            Box(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                Column(Modifier.width(contentWidth)) {
                    doc.tracks.forEach { track ->
                        TrackRow(
                            track = track,
                            doc = doc,
                            selectedClipId = selectedClipId,
                            pxPerSecond = pxPerSecond,
                            snapping = snapping,
                            playheadUs = playheadUs,
                            onSelect = onSelect,
                            onSeek = onSeek,
                            onMoveClip = onMoveClip,
                            onTrimStart = onTrimStart,
                            onTrimEnd = onTrimEnd,
                            onGestureFinished = onGestureFinished,
                            onClipMenu = onClipMenu
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                val playheadX: Dp = with(density) { (playheadUs / 1_000_000f * pxPerSecond).dp }
                Box(
                    modifier = Modifier
                        .offset(x = playheadX - 1.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(CutColors.Accent.copy(alpha = 0.95f))
                )
                Box(
                    modifier = Modifier
                        .offset(x = playheadX - 5.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(CutColors.Accent)
                )
            }
        }
    }
}

data class TimelineZoom(val pxPerSecond: Float) {
    fun zoomBy(factor: Float): TimelineZoom = TimelineZoom((pxPerSecond * factor).coerceIn(6f, 600f))
    companion object {
        val Default = TimelineZoom(56f)
    }
}

@Composable
private fun TrackLabel(track: Track) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight(track.type))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Icon(
                imageVector = when (track.type) {
                    TrackType.VIDEO -> Icons.Rounded.Movie
                    TrackType.AUDIO -> Icons.Rounded.Audiotrack
                    TrackType.TEXT -> Icons.Rounded.TextFields
                    TrackType.OVERLAY -> Icons.Rounded.Layers
                },
                contentDescription = track.type.name,
                tint = CutColors.trackColor(track.type.name),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                track.type.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = CutColors.TextTertiary,
                maxLines = 1
            )
            if (track.muted) {
                Icon(Icons.Rounded.VolumeOff, null, tint = CutColors.TextTertiary, modifier = Modifier.size(9.dp))
            }
        }
    }
}

private fun trackHeight(type: TrackType): Dp = when (type) {
    TrackType.VIDEO -> 56.dp
    TrackType.OVERLAY -> 38.dp
    TrackType.TEXT -> 34.dp
    TrackType.AUDIO -> 40.dp
}

@Composable
private fun TrackRow(
    track: Track,
    doc: ProjectDoc,
    selectedClipId: String?,
    pxPerSecond: Float,
    snapping: Boolean,
    playheadUs: Long,
    onSelect: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onMoveClip: (String, Long, String?, Boolean) -> Unit,
    onTrimStart: (String, Long, Boolean) -> Unit,
    onTrimEnd: (String, Long, Boolean) -> Unit,
    onGestureFinished: () -> Unit,
    onClipMenu: (Clip) -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight(track.type))
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CutColors.BackgroundSunken)
            .pointerInput(track.id) { detectTapGestures { onSelect(null) } }
    ) {
        track.sortedClips.forEach { clip ->
            val widthDp: Dp = with(density) { (clip.durationUs / 1_000_000f * pxPerSecond).dp }
            val offsetDp: Dp = with(density) { (clip.startUs / 1_000_000f * pxPerSecond).dp }
            ClipView(
                clip = clip,
                track = track,
                selected = clip.id == selectedClipId,
                widthDp = widthDp,
                pxPerSecond = pxPerSecond,
                snapping = snapping,
                doc = doc,
                playheadUs = playheadUs,
                modifier = Modifier.offset(x = offsetDp),
                onSelect = onSelect,
                onSeek = onSeek,
                onMoveClip = onMoveClip,
                onTrimStart = onTrimStart,
                onTrimEnd = onTrimEnd,
                onGestureFinished = onGestureFinished,
                onClipMenu = onClipMenu
            )
        }
    }
}

@Composable
private fun ClipView(
    clip: Clip,
    track: Track,
    selected: Boolean,
    widthDp: Dp,
    pxPerSecond: Float,
    snapping: Boolean,
    doc: ProjectDoc,
    playheadUs: Long,
    modifier: Modifier = Modifier,
    onSelect: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onMoveClip: (String, Long, String?, Boolean) -> Unit,
    onTrimStart: (String, Long, Boolean) -> Unit,
    onTrimEnd: (String, Long, Boolean) -> Unit,
    onGestureFinished: () -> Unit,
    onClipMenu: (Clip) -> Unit
) {
    val accent = CutColors.trackColor(track.type.name)
    val density = LocalDensity.current
    val gesture = remember(clip.id) { ClipGestureState() }
    val minWidth = 20.dp

    Box(
        modifier = modifier
            .width(if (widthDp < minWidth) minWidth else widthDp)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = if (selected) 0.58f else 0.32f),
                        accent.copy(alpha = if (selected) 0.40f else 0.20f)
                    )
                )
            )
            .border(
                width = if (selected) 1.8.dp else 1.dp,
                color = if (selected) Color.White else accent.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            )
            .pointerInput(clip.id) {
                detectTapGestures(
                    onTap = {
                        onSelect(clip.id)
                        onSeek(clip.startUs)
                    },
                    onLongPress = { onClipMenu(clip) }
                )
            }
            .pointerInput(clip.id, pxPerSecond, snapping) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onSelect(clip.id)
                        gesture.startUs = clip.startUs
                        gesture.endUs = clip.endUs
                        val edge = with(density) { 18.dp.toPx() }
                        gesture.mode = when {
                            offset.x < edge -> TrimMode.START
                            offset.x > size.width - edge -> TrimMode.END
                            else -> TrimMode.MOVE
                        }
                    },
                    onDragEnd = { gesture.mode = TrimMode.NONE; onGestureFinished() },
                    onDragCancel = { gesture.mode = TrimMode.NONE; onGestureFinished() }
                ) { change, dragAmount ->
                    change.consume()
                    val deltaUs = (dragAmount.x / pxPerSecond * 1_000_000f).roundToLong()
                    when (gesture.mode) {
                        TrimMode.MOVE -> {
                            gesture.startUs = (gesture.startUs + deltaUs).coerceAtLeast(0L)
                            var target = gesture.startUs
                            if (snapping) {
                                val points = TimelineManager.snapPoints(doc, clip.id, playheadUs)
                                target = TimelineManager.snapClipStart(target, clip.durationUs, points)
                            }
                            onMoveClip(clip.id, target, track.id, true)
                        }
                        TrimMode.START -> {
                            gesture.startUs = (gesture.startUs + deltaUs).coerceAtLeast(0L)
                            var target = gesture.startUs
                            if (snapping) {
                                target = TimelineManager.snap(target, TimelineManager.snapPoints(doc, clip.id, playheadUs))
                            }
                            onTrimStart(clip.id, target, true)
                        }
                        TrimMode.END -> {
                            gesture.endUs = (gesture.endUs + deltaUs).coerceAtLeast(clip.startUs + 80_000L)
                            var target = gesture.endUs
                            if (snapping) {
                                target = TimelineManager.snap(target, TimelineManager.snapPoints(doc, clip.id, playheadUs))
                            }
                            onTrimEnd(clip.id, target, true)
                        }
                        TrimMode.NONE -> Unit
                    }
                }
            }
    ) {
        // Keyframe timeline markers: one diamond per keyframe along the clip's top edge.
        if (clip.hasKeyframes) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                clip.keyframes.forEach { track2 ->
                    track2.sorted.forEach { key ->
                        val x = (key.timeUs.toFloat() / clip.durationUs.coerceAtLeast(1L)) * size.width
                        val y = 5f * density.density
                        val r = 3.2f * density.density
                        drawLine(
                            color = Color(0xFFFFD166),
                            start = Offset(x, y - r),
                            end = Offset(x + r, y),
                            strokeWidth = 2f * density.density
                        )
                        drawLine(
                            color = Color(0xFFFFD166),
                            start = Offset(x + r, y),
                            end = Offset(x, y + r),
                            strokeWidth = 2f * density.density
                        )
                        drawLine(
                            color = Color(0xFFFFD166),
                            start = Offset(x, y + r),
                            end = Offset(x - r, y),
                            strokeWidth = 2f * density.density
                        )
                        drawLine(
                            color = Color(0xFFFFD166),
                            start = Offset(x - r, y),
                            end = Offset(x, y - r),
                            strokeWidth = 2f * density.density
                        )
                    }
                }
            }
        }
        when (track.type) {
            TrackType.VIDEO, TrackType.OVERLAY -> if (clip.uri.isNotBlank()) {
                ClipFrame(
                    uri = clip.uri,
                    atUs = clip.sourceInUs,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f))
                        )
                    )
                )
            }
            TrackType.AUDIO -> Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val bars = (size.width / 4f).toInt().coerceAtLeast(4)
                for (i in 0 until bars) {
                    val h = size.height * (0.22f + 0.66f * abs(sin(i * 1.7f + clip.name.hashCode() * 0.001f)))
                    drawRect(
                        color = Color.White.copy(alpha = 0.45f),
                        topLeft = Offset(i * 4f, (size.height - h) / 2f),
                        size = Size(2f, h)
                    )
                }
            }
            TrackType.TEXT -> Unit
        }

        Text(
            text = clip.text?.text?.take(28) ?: clip.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 5.dp, vertical = 3.dp)
        )

        Row(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp)) {
            if (clip.muted) {
                Icon(Icons.Rounded.VolumeOff, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(9.dp))
            }
            if (clip.speed != 1f) {
                Text(
                    "${clip.speed}×",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
            if (clip.filter.isActive) {
                Text("FX", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.White.copy(alpha = 0.85f))
            }
        }

        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
            Box(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
        }
    }
}

private class ClipGestureState {
    var mode: TrimMode = TrimMode.NONE
    var startUs: Long = 0L
    var endUs: Long = 0L
}

private enum class TrimMode { NONE, START, END, MOVE }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRuler(pxPerSecond: Float, durationUs: Long) {
    val height = size.height
    drawRect(color = CutColors.BackgroundSunken, size = size)
    val step = rulerStep(pxPerSecond)
    val totalSeconds = durationUs / 1_000_000f + 30f
    var t = 0f
    while (t < totalSeconds) {
        val x = t * pxPerSecond
        drawLine(CutColors.BorderStrong, Offset(x, height - 9f), Offset(x, height), strokeWidth = 1.4f)
        var minor = t + step / 4f
        while (minor < t + step) {
            val mx = minor * pxPerSecond
            drawLine(CutColors.Border, Offset(mx, height - 4f), Offset(mx, height), strokeWidth = 1f)
            minor += step / 4f
        }
        t += step
    }
    val endX = durationUs / 1_000_000f * pxPerSecond
    drawLine(CutColors.Accent.copy(alpha = 0.6f), Offset(endX, 0f), Offset(endX, height), strokeWidth = 1.5f)
}

private fun rulerStep(pxPerSecond: Float): Float =
    listOf(0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f).firstOrNull { it * pxPerSecond >= 58f } ?: 60f

@Composable
private fun RulerLabels(
    pxPerSecond: Float,
    durationUs: Long,
    scrollPx: Float,
    modifier: Modifier = Modifier
) {
    val step = rulerStep(pxPerSecond)
    val totalSeconds = durationUs / 1_000_000f + 30f
    val density = LocalDensity.current
    val labels = remember(pxPerSecond, durationUs) {
        val list = ArrayList<Pair<Float, String>>()
        var t = 0f
        while (t < totalSeconds) {
            list += (t * pxPerSecond) to TimeText.clock((t * 1_000_000f).toLong())
            t += step
        }
        list
    }
    Box(modifier) {
        labels.forEach { (x, label) ->
            val offset: Dp = with(density) { (x - scrollPx).toDp() }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = CutColors.TextTertiary,
                modifier = Modifier.offset(x = offset, y = 2.dp)
            )
        }
    }
}
