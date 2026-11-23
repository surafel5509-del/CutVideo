package com.cutvideo.editor.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.Keyframe
import com.cutvideo.editor.domain.model.KeyframeInterpolation
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.MaskType
import com.cutvideo.editor.editor.KeyframeEngine
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.SegmentedControl
import com.cutvideo.editor.ui.components.ToggleRow
import com.cutvideo.editor.ui.components.ValueSlider
import com.cutvideo.editor.ui.theme.CutColors

private val keyframeProperties = listOf(
    KeyframeProperty.POSITION_X, KeyframeProperty.POSITION_Y, KeyframeProperty.SCALE,
    KeyframeProperty.ROTATION, KeyframeProperty.OPACITY, KeyframeProperty.BLUR,
    KeyframeProperty.GLOW, KeyframeProperty.EFFECT_INTENSITY, KeyframeProperty.VOLUME,
    KeyframeProperty.SPEED, KeyframeProperty.MASK_X, KeyframeProperty.MASK_Y,
    KeyframeProperty.MASK_SCALE, KeyframeProperty.MASK_ROTATION, KeyframeProperty.MASK_FEATHER,
    KeyframeProperty.MASK_OPACITY, KeyframeProperty.MASK_EXPANSION
)

// -------------------------------------------------------------------------------------------------
// Mask tool
// -------------------------------------------------------------------------------------------------

@Composable
fun MaskPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    var selectedMaskId by remember(clip.id, clip.masks) {
        mutableStateOf(clip.masks.firstOrNull()?.id)
    }
    val selected = clip.masks.firstOrNull { it.id == selectedMaskId } ?: clip.masks.firstOrNull()

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaskType.entries.filter { it != MaskType.FREEHAND }.forEach { type ->
                GhostButton("+ ${type.label}") { vm.addMask(type) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("+ Freehand") { vm.addMask(MaskType.FREEHAND) }
            if (clip.masks.isNotEmpty()) GhostButton("Delete mask") {
                selected?.let { vm.deleteMask(it.id) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Masks cut real alpha in the render pipeline (preview and export). Up to 2 stack.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextTertiary
        )
        Spacer(Modifier.height(10.dp))

        if (clip.masks.isEmpty()) {
            Text("No masks yet.", color = CutColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            return
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(clip.masks) { mask ->
                val active = mask.id == selected?.id
                Text(
                    mask.type.label + if (mask.invert) " (inv)" else "",
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) CutColors.Accent.copy(alpha = 0.22f) else CutColors.SurfaceHigh)
                        .clickable { selectedMaskId = mask.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (active) CutColors.Accent else CutColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val current = selected ?: return
        var live by remember { mutableStateOf(false) }
        fun edit(block: (com.cutvideo.editor.domain.model.MaskSpec) -> com.cutvideo.editor.domain.model.MaskSpec) {
            vm.updateMask(current.id, live, block)
        }
        ToggleRow(title = "Live drag (no undo per step)", checked = live, onCheckedChange = { live = it })
        Spacer(Modifier.height(4.dp))

        ValueSlider("Position X", current.xFrac,
            onValueChange = { edit { m -> m.copy(xFrac = it) } }, valueRange = -0.5f..1.5f, defaultValue = 0.5f)
        ValueSlider("Position Y", current.yFrac,
            onValueChange = { edit { m -> m.copy(yFrac = it) } }, valueRange = -0.5f..1.5f, defaultValue = 0.5f)
        ValueSlider("Scale", current.scale,
            onValueChange = { edit { m -> m.copy(scale = it) } }, valueRange = 0.02f..2f, defaultValue = 0.35f)
        ValueSlider("Rotation", current.rotationDegrees,
            onValueChange = { edit { m -> m.copy(rotationDegrees = it) } }, valueRange = -180f..180f)
        ValueSlider("Feather", current.feather,
            onValueChange = { edit { m -> m.copy(feather = it) } }, valueRange = 0f..1f, defaultValue = 0.15f)
        ValueSlider("Opacity", current.opacity,
            onValueChange = { edit { m -> m.copy(opacity = it) } }, valueRange = 0f..1f, defaultValue = 1f)
        ValueSlider("Expand / contract", current.expansion,
            onValueChange = { edit { m -> m.copy(expansion = it) } }, valueRange = -1f..1f)
        if (current.type == MaskType.RECT) {
            ValueSlider("Corner roundness", current.roundness,
                onValueChange = { edit { m -> m.copy(roundness = it) } }, valueRange = 0f..1f, defaultValue = 0.2f)
        }
        ToggleRow(title = "Invert mask", checked = current.invert, onCheckedChange = { edit { m -> m.copy(invert = it) } })

        if (current.type == MaskType.FREEHAND) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Draw on the preview to paint the mask; brush marks are kept as editable strokes.",
                style = MaterialTheme.typography.bodySmall,
                color = CutColors.TextTertiary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Clear strokes") { edit { m -> m.copy(strokes = emptyList()) } }
                GhostButton("Refine: erode") { edit { m -> m.copy(expansion = (m.expansion - 0.15f).coerceIn(-1f, 1f)) } }
                GhostButton("Refine: dilate") { edit { m -> m.copy(expansion = (m.expansion + 0.15f).coerceIn(-1f, 1f)) } }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Keyframe tool
// -------------------------------------------------------------------------------------------------

@Composable
fun KeyframePanel(clip: Clip?, playhead: Long, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    var property by remember { mutableStateOf(KeyframeProperty.POSITION_X) }
    val track = clip.keyframeTrack(property)
    val localUs = (playhead - clip.startUs).coerceIn(0L, clip.durationUs)

    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keyframeProperties) { prop ->
                val active = prop == property
                Text(
                    prop.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) CutColors.Accent.copy(alpha = 0.22f) else CutColors.SurfaceHigh)
                        .clickable { property = prop }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (active) CutColors.Accent else CutColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("◆ Add keyframe here") { vm.addKeyframe(property) }
            GhostButton("Clear this track") { vm.clearKeyframes(property) }
            GhostButton("Clear all") { vm.clearKeyframes() }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Keyframes animate preview and export through the same engine. Times are clip-local.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextTertiary
        )
        Spacer(Modifier.height(10.dp))

        Text("Curve: ${property.label}", fontWeight = FontWeight.SemiBold, color = CutColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        if (track == null || track.isEmpty) {
            Text(
                "No keyframes on this property yet — it stays at " +
                    "%.2f".format(clip.let { vm.editor.baseValue(it, property) }),
                color = CutColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            track.sorted.forEach { key ->
                KeyframeRow(
                    key = key,
                    property = property,
                    clip = clip,
                    vm = vm,
                    highlight = key.timeUs == localUs
                )
            }
        }
    }
}

@Composable
private fun KeyframeRow(
    key: Keyframe,
    property: KeyframeProperty,
    clip: Clip,
    vm: EditorViewModel,
    highlight: Boolean
) {
    var interpolationOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            "◆",
            color = if (highlight) CutColors.Accent else CutColors.TextSecondary,
            modifier = Modifier.width(22.dp)
        )
        Text(
            com.cutvideo.editor.core.TimeText.clockWithFrames(key.timeUs, 30),
            color = CutColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(92.dp)
        )
        Text(
            "%.3f".format(key.value),
            color = CutColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(58.dp)
        )
        Text(
            key.interpolation.label,
            color = CutColors.Accent,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { interpolationOpen = !interpolationOpen }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        GhostButton("Seek") { vm.seekTo(clip.startUs + key.timeUs) }
        GhostButton("✕") { vm.deleteKeyframe(property, key.timeUs) }
    }
    if (interpolationOpen) {
        SegmentedControl(
            options = KeyframeInterpolation.entries.map { it.label },
            selectedIndex = KeyframeInterpolation.entries.indexOf(key.interpolation)
        ) { index ->
            vm.setKeyframeInterpolation(property, key.timeUs, KeyframeInterpolation.entries[index])
            interpolationOpen = false
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Graph / curve editor
// -------------------------------------------------------------------------------------------------
@Composable
fun GraphPanel(clip: Clip?, playhead: Long, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    var property by remember { mutableStateOf(KeyframeProperty.SPEED) }
    val track = clip.keyframeTrack(property)
    val localUs = (playhead - clip.startUs).coerceIn(0L, clip.durationUs)

    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keyframeProperties) { prop ->
                val active = prop == property
                Text(
                    prop.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) CutColors.Accent.copy(alpha = 0.22f) else CutColors.SurfaceHigh)
                        .clickable { property = prop }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (active) CutColors.Accent else CutColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("◆ Add keyframe") { vm.addKeyframeAt(property, 0L) }
            if (property == KeyframeProperty.SPEED) {
                GhostButton("Slow ramp") {
                    // Real speed ramp: 1× at the clip start easing to 0.5× near the end.
                    vm.clearKeyframes(KeyframeProperty.SPEED)
                    vm.addKeyframeAt(KeyframeProperty.SPEED, 0L)
                    vm.addKeyframeAt(KeyframeProperty.SPEED, clip.durationUs * 3 / 4)
                    vm.moveKeyframe(
                        KeyframeProperty.SPEED,
                        clip.durationUs * 3 / 4,
                        clip.durationUs * 3 / 4,
                        0.5f
                    )
                }
            }
            GhostButton("Clear track") { vm.clearKeyframes(property) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Drag dots to retime/revalue. Bézier segments show their handles — drag those for " +
                "custom velocity. SPEED curves become real speed ramps in export.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextTertiary
        )
        Spacer(Modifier.height(8.dp))

        CurveEditor(
            clip = clip,
            property = property,
            track = track,
            localUs = localUs,
            onMove = { from, to, value -> vm.moveKeyframe(property, from, to, value) },
            onSeek = { vm.seekTo(clip.startUs + it) },
            onHandles = { t, inX, inY, outX, outY ->
                vm.setKeyframeHandles(property, t, inX, inY, outX, outY)
            }
        )
    }
}

/**
 * The real curve editor: draws the evaluated curve (through [KeyframeEngine]), keyframe dots, and
 * draggable Bézier handles. All edits go through the view-model so undo/redo and persistence cover
 * them.
 */
@Composable
private fun CurveEditor(
    clip: Clip,
    property: KeyframeProperty,
    track: com.cutvideo.editor.domain.model.KeyframeTrack?,
    localUs: Long,
    onMove: (fromUs: Long, toUs: Long, value: Float?) -> Unit,
    onSeek: (us: Long) -> Unit,
    onHandles: (timeUs: Long, inX: Float, inY: Float, outX: Float, outY: Float) -> Unit
) {
    val line = CutColors.Accent
    val handleColor = CutColors.TextSecondary
    val playheadColor = CutColors.Warning
    val textColor = CutColors.TextTertiary

    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragHandle by remember { mutableStateOf<String?>(null) } // "in:<timeUs>" / "out:<timeUs>"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CutColors.SurfaceHigh)
            .border(1.dp, CutColors.Border, RoundedCornerShape(12.dp))
            .pointerInput(clip.id, property) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val keys = track?.sorted ?: emptyList()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val yFor = { v: Float ->
                            h * (1f - (v - property.minValue) / (property.maxValue - property.minValue))
                        }
                        val xFor = { t: Long -> w * (if (clip.durationUs > 0) t.toFloat() / clip.durationUs else 0f) }
                        // Handles first (smaller targets win), then dots.
                        val handle = keys.firstOrNull { key ->
                            key.interpolation == KeyframeInterpolation.BEZIER &&
                                (
                                    (pos - Offset(xFor(key.timeUs) - key.bezierInX * 60f, yFor(key.value + key.bezierInY * 20f))).getDistance() < 26f ||
                                        (pos - Offset(xFor(key.timeUs) + key.bezierOutX * 60f, yFor(key.value + key.bezierOutY * 20f))).getDistance() < 26f
                                    )
                        }
                        when {
                            handle != null && pos.x < xFor(handle.timeUs) ->
                                dragHandle = "in:${handle.timeUs}"
                            handle != null -> dragHandle = "out:${handle.timeUs}"
                            else -> {
                                val dot = keys.minByOrNull { (pos - Offset(xFor(it.timeUs), yFor(it.value))).getDistance() }
                                if (dot != null && (pos - Offset(xFor(dot.timeUs), yFor(dot.value))).getDistance() < 34f) {
                                    dragKey = dot.timeUs
                                    onSeek(dot.timeUs)
                                } else {
                                    val t = ((pos.x / size.width) * clip.durationUs).toLong().coerceIn(0L, clip.durationUs)
                                    onSeek(t)
                                }
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        val pos = change.position
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val t = ((pos.x / w) * clip.durationUs).toLong().coerceIn(0L, clip.durationUs)
                        val v = (property.minValue + (1f - pos.y / h) * (property.maxValue - property.minValue))
                            .coerceIn(property.minValue, property.maxValue)
                        dragKey?.let { keyTime ->
                            onMove(keyTime, t, v)
                            dragKey = t
                        }
                        dragHandle?.let { handle ->
                            val (kind, timeUs) = handle.split(":")
                            val keyTime = timeUs.toLong()
                            val key = track?.sorted?.firstOrNull { it.timeUs == keyTime } ?: return@let
                            val inX = ((xForNorm(keyTime, clip.durationUs, w) - pos.x) / 60f).coerceIn(0f, 1f)
                            val inY = ((yForNorm(key.value, property, h) - pos.y) / 40f).coerceIn(-1f, 2f)
                            val outX = ((pos.x - xForNorm(keyTime, clip.durationUs, w)) / 60f).coerceIn(0f, 1f)
                            val outY = ((pos.y - yForNorm(key.value, property, h)) / -40f).coerceIn(-1f, 2f)
                            if (kind == "in") {
                                onHandles(keyTime, inX, inY, key.bezierOutX, key.bezierOutY)
                            } else {
                                onHandles(keyTime, key.bezierInX, key.bezierInY, outX, outY)
                            }
                        }
                    },
                    onDragEnd = {
                        dragKey = null
                        dragHandle = null
                    }
                )
            }
            .pointerInput(clip.id, property) {
                detectTapGestures { pos ->
                    val t = ((pos.x / size.width) * clip.durationUs).toLong().coerceIn(0L, clip.durationUs)
                    onSeek(t)
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val w = size.width
            val h = size.height
            val yFor = { v: Float -> h * (1f - (v - property.minValue) / (property.maxValue - property.minValue)) }
            val xFor = { t: Long -> w * (if (clip.durationUs > 0) t.toFloat() / clip.durationUs else 0f) }

            // Grid
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(Color(0x14FFFFFF), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            // Curve through the real evaluation engine
            val curve = KeyframeEngine.sampleCurve(
                track ?: com.cutvideo.editor.domain.model.KeyframeTrack(property, emptyList()),
                0L,
                clip.durationUs,
                steps = 120
            )
            if (curve.size > 1) {
                val path = Path()
                curve.forEachIndexed { i, (fx, v) ->
                    val x = fx * w
                    val y = yFor(v.coerceIn(property.minValue, property.maxValue))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
            }

            // Playhead
            drawLine(
                playheadColor,
                Offset(xFor(localUs), 0f),
                Offset(xFor(localUs), h),
                strokeWidth = 2f
            )

            // Keyframes + handles
            track?.sorted?.forEach { key ->
                val x = xFor(key.timeUs)
                val y = yFor(key.value.coerceIn(property.minValue, property.maxValue))
                if (key.interpolation == KeyframeInterpolation.BEZIER) {
                    val hx = x - key.bezierInX * 60f
                    val hy = y + key.bezierInY * 20f
                    val ox = x + key.bezierOutX * 60f
                    val oy = y + key.bezierOutY * 20f
                    drawLine(handleColor, Offset(x, y), Offset(hx, hy), strokeWidth = 1.5f)
                    drawLine(handleColor, Offset(x, y), Offset(ox, oy), strokeWidth = 1.5f)
                    drawCircle(handleColor, 6f, Offset(hx, hy))
                    drawCircle(handleColor, 6f, Offset(ox, oy))
                }
                drawCircle(Color(0xFF111114), 10f, Offset(x, y))
                drawCircle(line, 7f, Offset(x, y))
            }
        }

        Text(
            "%.2f — %.2f".format(property.minValue, property.maxValue),
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(6.dp)
        )
    }
}

private fun xForNorm(t: Long, durationUs: Long, w: Float): Float =
    w * (if (durationUs > 0) t.toFloat() / durationUs else 0f)

private fun yForNorm(v: Float, property: KeyframeProperty, h: Float): Float =
    h * (1f - (v - property.minValue) / (property.maxValue - property.minValue))

// -------------------------------------------------------------------------------------------------
// Cutout entry panel
// -------------------------------------------------------------------------------------------------

@Composable
fun CutoutPanel(vm: EditorViewModel, onOpenCutout: () -> Unit, onPickImageLayer: () -> Unit) {
    Column {
        Text(
            "Manual photo background remover",
            style = MaterialTheme.typography.titleMedium,
            color = CutColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Erase and restore brushes with size, hardness and opacity, zoom and pan, undo/redo, " +
                "edge refinement, a checkerboard alpha preview and transparent PNG export. The " +
                "original photo is never modified.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextSecondary
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Open photo cutout", onClick = onOpenCutout)
            GhostButton("Add image layer", onClick = onPickImageLayer)
        }
    }
}
