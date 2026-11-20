package com.cutvideo.editor.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.domain.model.AnimationCatalog
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.EffectsCatalog
import com.cutvideo.editor.domain.model.FilterCatalog
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.model.TransitionCatalog
import com.cutvideo.editor.domain.model.TransitionType
import com.cutvideo.editor.nativebridge.NativeEngine
import com.cutvideo.editor.ui.components.ColorPalette
import com.cutvideo.editor.ui.components.EffectCard
import com.cutvideo.editor.ui.components.FilterCard
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.MotionCard
import com.cutvideo.editor.ui.components.MotionPreview
import com.cutvideo.editor.ui.components.OptionRow
import com.cutvideo.editor.ui.components.StatusBadge
import com.cutvideo.editor.ui.components.TextPreviewThumb
import com.cutvideo.editor.ui.components.ToggleRow
import com.cutvideo.editor.ui.components.ValueSlider
import com.cutvideo.editor.ui.components.rememberLookPreview
import com.cutvideo.editor.ui.theme.CutColors
import kotlin.math.roundToInt

private val textColors = listOf(
    Color.White, Color(0xFFFDE047), Color(0xFFFB7185), Color(0xFFA855F7),
    Color(0xFF38BDF8), Color(0xFF22C55E), Color(0xFFF97316), Color(0xFF111114)
)
private val chromaColors = listOf(
    Color(0xFF00FF00), Color(0xFF0066FF), Color(0xFF00E5FF), Color(0xFFFFFFFF), Color(0xFF111114)
)

/**
 * Bottom tool panel. Every control here mutates the document through the view-model — there are no
 * decorative controls: disabled features are labelled instead of pretending to work.
 */
@Composable
fun EditorToolPanel(
    tool: EditorTool,
    doc: ProjectDoc,
    clip: Clip?,
    vm: EditorViewModel,
    nativeEngine: NativeEngine,
    onOpenTextEditor: () -> Unit,
    onPickAudio: () -> Unit,
    onImportSrt: () -> Unit,
    onExportSrt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filterSource by vm.filterSource.collectAsStateWithLifecycle()
    val playhead by vm.playheadUs.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                tool.label,
                style = MaterialTheme.typography.titleLarge,
                color = CutColors.TextPrimary
            )
            clip?.let {
                Text(
                    "${it.name.take(22)} • ${TimeText.clock(it.durationUs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        when (tool) {
            EditorTool.TRIM -> TrimPanel(clip, playhead, vm)
            EditorTool.SPLIT -> SplitPanel(clip, playhead, vm)
            EditorTool.SPEED -> SpeedPanel(clip, vm)
            EditorTool.ADJUST -> AdjustPanel(clip, vm)
            EditorTool.FILTER -> FilterPanel(clip, filterSource, doc, nativeEngine, vm)
            EditorTool.EFFECTS -> EffectsPanel(clip, filterSource, doc, nativeEngine, vm)
            EditorTool.ANIMATION -> AnimationPanel(clip, vm)
            EditorTool.TRANSITION -> TransitionPanel(clip, doc, vm)
            EditorTool.CROP -> CropPanel(clip, vm)
            EditorTool.TEXT -> TextPanel(doc, clip, vm, onOpenTextEditor)
            EditorTool.AUDIO -> AudioPanel(clip, vm, onPickAudio)
            EditorTool.CHROMA -> ChromaPanel(clip, vm)
            EditorTool.PIP -> PipPanel(clip, vm)
            EditorTool.CAPTIONS -> CaptionsPanel(doc, playhead, vm, onImportSrt, onExportSrt)
            EditorTool.NONE -> Box(Modifier.fillMaxWidth().height(40.dp))
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun NeedsClip() {
    Text(
        "Select a clip on the timeline to use this tool.",
        style = MaterialTheme.typography.bodyMedium,
        color = CutColors.TextSecondary
    )
}

@Composable
private fun TrimPanel(clip: Clip?, playhead: Long, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    val sourceOut = (clip.sourceInUs + clip.sourceSpanUs).coerceAtMost(clip.sourceDurationUs)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Trim start here", onClick = { vm.trimStart(clip.id, playhead, false) })
            GhostButton("Trim end here", onClick = { vm.trimEnd(clip.id, playhead, false) })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Reset trim", onClick = {
                vm.trimStart(clip.id, clip.startUs - clip.sourceInUs / clip.speed, false)
            })
            GhostButton("Duplicate", onClick = { vm.duplicateSelected() })
            GhostButton("Delete", onClick = { vm.deleteSelected() })
        }
        Spacer(Modifier.height(12.dp))
        InfoRow("Timeline in", TimeText.clockWithFrames(clip.startUs, 30))
        InfoRow("Timeline out", TimeText.clockWithFrames(clip.endUs, 30))
        InfoRow("Source in", TimeText.clockWithFrames(clip.sourceInUs, 30))
        InfoRow("Source out", TimeText.clockWithFrames(sourceOut, 30))
        InfoRow("Speed", "${clip.speed}×")
    }
}

@Composable
private fun SplitPanel(clip: Clip?, playhead: Long, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    Column {
        Text(
            "Splitting cuts the selected clip (or the clip under the playhead) at the cursor position.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        GhostButton("Split at playhead", onClick = { vm.split() }, icon = Icons.Rounded.ContentCut)
        Spacer(Modifier.height(10.dp))
        InfoRow("Clip", clip.name.take(28))
        InfoRow("Playhead", TimeText.clockWithFrames(playhead, 30))
    }
}

@Composable
private fun SpeedPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    Column {
        OptionRow(
            options = listOf("0.25×", "0.5×", "1×", "1.5×"),
            selectedIndex = listOf(0.25f, 0.5f, 1f, 1.5f).indexOfFirst { kotlin.math.abs(it - clip.speed) < 0.01f },
            onSelect = { index -> vm.setSpeed(listOf(0.25f, 0.5f, 1f, 1.5f)[index]) }
        )
        Spacer(Modifier.height(8.dp))
        OptionRow(
            options = listOf("2×", "3×", "4×", "Freeze"),
            selectedIndex = listOf(2f, 3f, 4f).indexOfFirst { kotlin.math.abs(it - clip.speed) < 0.01f },
            onSelect = { index ->
                if (index == 3) vm.freezeFrame() else vm.setSpeed(listOf(2f, 3f, 4f)[index])
            }
        )
        Spacer(Modifier.height(10.dp))
        ValueSlider(
            label = "Custom speed",
            value = clip.speed,
            valueRange = 0.25f..4f,
            defaultValue = 1f,
            format = { "${(it * 100).roundToInt() / 100f}×" },
            onValueChange = { vm.setSpeed(it) }
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Reverse", onClick = { vm.reverseSelected() })
            Spacer(Modifier.width(8.dp))
            StatusBadge("Not available")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Reverse requires full random-access decoding, which Media3 does not expose for arbitrary " +
                "source formats. It is listed as unavailable instead of shipping a fake toggle.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextTertiary
        )
    }
}

@Composable
private fun AdjustPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    val a = clip.adjust
    Column {
        ValueSlider("Brightness", a.brightness, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(brightness = v)) } })
        ValueSlider("Contrast", a.contrast, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(contrast = v)) } })
        ValueSlider("Saturation", a.saturation, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(saturation = v)) } })
        ValueSlider("Exposure", a.exposure, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(exposure = v)) } })
        ValueSlider("Temperature", a.temperature, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(temperature = v)) } })
        ValueSlider("Tint", a.tint, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(tint = v)) } })
        ValueSlider("Vignette", a.vignette, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(vignette = v)) } }, valueRange = 0f..1f)
        ValueSlider("Sharpen", a.sharpen, { v -> vm.updateAdjustments { c -> c.copy(adjust = c.adjust.copy(sharpen = v)) } }, valueRange = 0f..1f)
        ValueSlider(
            "Blur",
            clip.blur,
            { v -> vm.updateAdjustments { c -> c.copy(blur = v) } },
            valueRange = 0f..24f,
            format = { "${it.roundToInt()}px" }
        )
        Spacer(Modifier.height(6.dp))
        GhostButton("Reset all", onClick = {
            vm.updateAdjustments(live = false) { c ->
                c.copy(adjust = com.cutvideo.editor.domain.model.Adjustments(), blur = 0f)
            }
        })
    }
}

@Composable
private fun FilterPanel(
    clip: Clip?,
    source: android.graphics.Bitmap?,
    doc: ProjectDoc,
    nativeEngine: NativeEngine,
    vm: EditorViewModel
) {
    if (clip == null) return NeedsClip()
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(FilterCatalog.presets, key = { it.id }) { preset ->
                val preview = rememberLookPreview(
                    source = source,
                    clip = clip,
                    aspect = doc.aspect,
                    nativeEngine = nativeEngine,
                    filter = preset
                )
                FilterCard(
                    preset = preset,
                    preview = preview,
                    selected = clip.filter.id == preset.id,
                    onClick = { vm.setFilter(preset.id) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ValueSlider(
            label = "Filter strength",
            value = clip.filter.intensity,
            valueRange = 0f..1f,
            defaultValue = 1f,
            format = { "${(it * 100).roundToInt()}%" },
            onValueChange = { vm.setFilterIntensity(it) }
        )
    }
}

@Composable
private fun EffectsPanel(
    clip: Clip?,
    source: android.graphics.Bitmap?,
    doc: ProjectDoc,
    nativeEngine: NativeEngine,
    vm: EditorViewModel
) {
    if (clip == null) return NeedsClip()
    val active = clip.effects.firstOrNull()
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(EffectsCatalog.all(), key = { it.id }) { preset ->
                val applied = clip.effects.any { it.id == preset.id }
                val preview = rememberLookPreview(
                    source = source,
                    clip = clip,
                    aspect = doc.aspect,
                    nativeEngine = nativeEngine,
                    effect = preset,
                    effectIntensity = preset.defaultIntensity
                )
                EffectCard(
                    preset = preset,
                    preview = preview,
                    applied = applied,
                    onClick = { vm.toggleEffect(preset.id, preset.defaultIntensity) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Up to 4 effects stack per clip (GPU limit). Applied in the order you tap them.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextTertiary
        )
        if (active != null) {
            Spacer(Modifier.height(6.dp))
            ValueSlider(
                label = "Strength • ${EffectsCatalog.preset(active.id).name}",
                value = active.intensity,
                valueRange = 0f..1f,
                defaultValue = 0.5f,
                format = { "${(it * 100).roundToInt()}%" },
                onValueChange = { vm.setEffectIntensity(active.id, it) }
            )
        }
        Spacer(Modifier.height(6.dp))
        GhostButton("Clear effects", onClick = { vm.updateAdjustments(live = false) { it.copy(effects = emptyList()) } })
    }
}

@Composable
private fun AnimationPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    Column {
        Text("IN", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AnimationCatalog.presets, key = { "in-${it.type}" }) { preset ->
                MotionCard(
                    title = preset.name,
                    subtitle = "in",
                    accent = preset.accent,
                    selected = clip.animationIn.type == preset.type,
                    preview = MotionPreview.Animation(preset.type),
                    onClick = { vm.setAnimation(preset.type, clip.animationOut.type, clip.animationIn.durationUs) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("OUT", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AnimationCatalog.presets, key = { "out-${it.type}" }) { preset ->
                MotionCard(
                    title = preset.name,
                    subtitle = "out",
                    accent = preset.accent,
                    selected = clip.animationOut.type == preset.type,
                    preview = MotionPreview.Animation(preset.type),
                    onClick = { vm.setAnimation(clip.animationIn.type, preset.type, clip.animationIn.durationUs) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ValueSlider(
            label = "Animation length",
            value = clip.animationIn.durationUs / 1_000_000f,
            valueRange = 0.1f..2f,
            defaultValue = 0.5f,
            format = { String.format("%.2fs", it) },
            onValueChange = {
                vm.setAnimation(clip.animationIn.type, clip.animationOut.type, (it * 1_000_000f).toLong())
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Apply IN to all", onClick = { vm.applyAnimationToAll(clip.animationIn.type, true) })
            GhostButton("Apply OUT to all", onClick = { vm.applyAnimationToAll(clip.animationOut.type, false) })
        }
    }
}

@Composable
private fun TransitionPanel(clip: Clip?, doc: ProjectDoc, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    val videoClips = doc.videoClips().sortedBy { it.startUs }
    val index = videoClips.indexOfFirst { it.id == clip.id }
    val hasNext = index >= 0 && index < videoClips.lastIndex
    Column {
        if (!hasNext) {
            Text(
                "Transitions sit between two adjacent clips. Select a clip that has a neighbour after it.",
                style = MaterialTheme.typography.bodySmall,
                color = CutColors.TextSecondary
            )
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TransitionCatalog.presets, key = { it.type.name }) { preset ->
                MotionCard(
                    title = preset.name,
                    subtitle = preset.description,
                    accent = preset.accent,
                    selected = clip.transitionOut?.type == preset.type,
                    preview = MotionPreview.Transition(preset.type),
                    onClick = { vm.setTransition(preset.type, clip.transitionOut?.durationUs ?: 600_000L) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ValueSlider(
            label = "Duration",
            value = (clip.transitionOut?.durationUs ?: 600_000L) / 1_000_000f,
            valueRange = 0.1f..2f,
            defaultValue = 0.6f,
            format = { String.format("%.2fs", it) },
            onValueChange = {
                vm.setTransition(clip.transitionOut?.type ?: TransitionType.FADE, (it * 1_000_000f).toLong())
            }
        )
        GhostButton("Remove transition", onClick = { vm.clearTransition() })
    }
}

@Composable
private fun CropPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    val t = clip.transform
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("⟲ 90°", onClick = { vm.rotate(-90f) })
            GhostButton("⟳ 90°", onClick = { vm.rotate(90f) })
            GhostButton("Reset", onClick = {
                vm.updateAdjustments(live = false) { it.copy(transform = com.cutvideo.editor.domain.model.Transform()) }
            })
        }
        Spacer(Modifier.height(10.dp))
        ValueSlider("Crop left", t.cropLeft, { v -> vm.setCrop(v, t.cropTop, t.cropRight, t.cropBottom) }, valueRange = 0f..0.45f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Crop right", t.cropRight, { v -> vm.setCrop(t.cropLeft, t.cropTop, v, t.cropBottom) }, valueRange = 0f..0.45f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Crop top", t.cropTop, { v -> vm.setCrop(t.cropLeft, v, t.cropRight, t.cropBottom) }, valueRange = 0f..0.45f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Crop bottom", t.cropBottom, { v -> vm.setCrop(t.cropLeft, t.cropTop, t.cropRight, v) }, valueRange = 0f..0.45f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Scale", t.scaleX, { v -> vm.setScale(v) }, valueRange = 0.3f..3f, defaultValue = 1f, format = { String.format("%.2f×", it) })
        ValueSlider("Rotation", t.rotationDegrees, { v -> vm.updateAdjustments { c -> c.copy(transform = c.transform.copy(rotationDegrees = v)) } }, valueRange = -180f..180f, format = { "${it.roundToInt()}°" })
    }
}

@Composable
private fun TextPanel(doc: ProjectDoc, clip: Clip?, vm: EditorViewModel, onOpenTextEditor: () -> Unit) {
    val textClips = doc.textClips().sortedBy { it.startUs }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Add text", onClick = { vm.addText() }, icon = Icons.Rounded.Add)
            GhostButton("Text editor", onClick = onOpenTextEditor, icon = Icons.Rounded.FileOpen)
        }
        if (textClips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(textClips, key = { it.id }) { textClip ->
                    TextPreviewThumb(
                        spec = textClip.text ?: TextSpec(),
                        selected = textClip.id == clip?.id,
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        }
        val textClip = clip?.takeIf { it.trackType == TrackType.TEXT }
        if (textClip?.text != null) {
            val spec = textClip.text
            Spacer(Modifier.height(10.dp))
            Text("Selected: ${textClip.name}", style = MaterialTheme.typography.labelMedium, color = CutColors.TextSecondary)
            ValueSlider(
                "Font size",
                spec.sizeSp,
                { v -> vm.updateSelectedText(spec.copy(sizeSp = v)) },
                valueRange = 12f..96f,
                defaultValue = 36f,
                format = { "${it.roundToInt()}sp" }
            )
            ValueSlider(
                "Shadow",
                spec.shadow.radius,
                { v -> vm.updateSelectedText(spec.copy(shadow = spec.shadow.copy(radius = v, enabled = v > 0.2f))) },
                valueRange = 0f..24f,
                defaultValue = 0f,
                format = { "${it.roundToInt()}px" }
            )
            Spacer(Modifier.height(6.dp))
            ColorPalette(
                colors = textColors,
                selected = Color(spec.color),
                onSelect = { color -> vm.updateSelectedText(spec.copy(color = color.value.toLong().let { android.graphics.Color.argb(
                    (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
                ) })) }
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Bold",
                checked = spec.bold,
                onCheckedChange = { v -> vm.updateSelectedText(spec.copy(bold = v)) }
            )
            ToggleRow(
                title = "Outline",
                checked = spec.outline.enabled,
                onCheckedChange = { v -> vm.updateSelectedText(spec.copy(outline = spec.outline.copy(enabled = v))) }
            )
        }
    }
}

@Composable
private fun AudioPanel(clip: Clip?, vm: EditorViewModel, onPickAudio: () -> Unit) {
    if (clip == null) return NeedsClip()
    Column {
        ValueSlider(
            "Volume",
            clip.volume,
            { v -> vm.setVolume(v) },
            valueRange = 0f..2f,
            defaultValue = 1f,
            format = { "${(it * 100).roundToInt()}%" }
        )
        ToggleRow(
            title = "Mute this clip",
            checked = clip.muted,
            onCheckedChange = { vm.toggleClipMute() }
        )
        ValueSlider(
            "Fade in",
            clip.fadeInUs / 1_000_000f,
            { v -> vm.setFades((v * 1_000_000f).toLong(), clip.fadeOutUs) },
            valueRange = 0f..5f,
            defaultValue = 0f,
            format = { String.format("%.2fs", it) }
        )
        ValueSlider(
            "Fade out",
            clip.fadeOutUs / 1_000_000f,
            { v -> vm.setFades(clip.fadeInUs, (v * 1_000_000f).toLong()) },
            valueRange = 0f..5f,
            defaultValue = 0f,
            format = { String.format("%.2fs", it) }
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Add music", onClick = onPickAudio, icon = Icons.Rounded.MusicNote)
            GhostButton("Extract audio", onClick = { vm.extractAudio() })
        }
    }
}

@Composable
private fun ChromaPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    Column {
        ToggleRow(
            title = "Chroma key",
            subtitle = "Remove a colour background in real time",
            checked = clip.chroma.enabled,
            onCheckedChange = { vm.setChroma(it) }
        )
        Spacer(Modifier.height(6.dp))
        Text("Key colour", style = MaterialTheme.typography.labelMedium, color = CutColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            chromaColors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                        .border(
                            2.dp,
                            if (clip.chroma.keyColor == color.value.toLong().let {
                                    android.graphics.Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                                }) Color.White else CutColors.Border,
                            RoundedCornerShape(50)
                        )
                        .clickable {
                            val argb = android.graphics.Color.rgb(
                                (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
                            )
                            vm.setChromaParams(argb, clip.chroma.similarity, clip.chroma.smoothness, clip.chroma.spill)
                        }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ValueSlider("Similarity", clip.chroma.similarity, { v -> vm.setChromaParams(clip.chroma.keyColor, v, clip.chroma.smoothness, clip.chroma.spill) }, valueRange = 0.02f..1f, defaultValue = 0.35f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Smoothness", clip.chroma.smoothness, { v -> vm.setChromaParams(clip.chroma.keyColor, clip.chroma.similarity, v, clip.chroma.spill) }, valueRange = 0f..0.9f, defaultValue = 0.12f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Spill", clip.chroma.spill, { v -> vm.setChromaParams(clip.chroma.keyColor, clip.chroma.similarity, clip.chroma.smoothness, v) }, valueRange = 0f..1f, defaultValue = 0.3f, format = { "${(it * 100).roundToInt()}%" })
    }
}

@Composable
private fun PipPanel(clip: Clip?, vm: EditorViewModel) {
    if (clip == null) return NeedsClip()
    Column {
        ToggleRow(
            title = "Picture in picture",
            subtitle = "Overlay this clip on top of the main video",
            checked = clip.pip.enabled,
            onCheckedChange = { vm.setPip(it) }
        )
        if (clip.trackType != TrackType.OVERLAY) {
            Spacer(Modifier.height(6.dp))
            Text(
                "PiP works on clips placed on the Overlay track. Use the overlay action in the clip menu to move it.",
                style = MaterialTheme.typography.bodySmall,
                color = CutColors.TextTertiary
            )
        }
        Spacer(Modifier.height(6.dp))
        ValueSlider("Size", clip.pip.scale, { v -> vm.setPipParams(v, clip.pip.offsetXFrac, clip.pip.offsetYFrac, clip.pip.cornerRadiusDp, clip.pip.borderWidthDp) }, valueRange = 0.15f..0.9f, defaultValue = 0.4f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Position X", clip.pip.offsetXFrac, { v -> vm.setPipParams(clip.pip.scale, v, clip.pip.offsetYFrac, clip.pip.cornerRadiusDp, clip.pip.borderWidthDp) }, valueRange = -0.4f..0.4f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Position Y", clip.pip.offsetYFrac, { v -> vm.setPipParams(clip.pip.scale, clip.pip.offsetXFrac, v, clip.pip.cornerRadiusDp, clip.pip.borderWidthDp) }, valueRange = -0.4f..0.4f, format = { "${(it * 100).roundToInt()}%" })
        ValueSlider("Corner radius", clip.pip.cornerRadiusDp, { v -> vm.setPipParams(clip.pip.scale, clip.pip.offsetXFrac, clip.pip.offsetYFrac, v, clip.pip.borderWidthDp) }, valueRange = 0f..40f, defaultValue = 12f, format = { "${it.roundToInt()}dp" })
        ValueSlider("Border", clip.pip.borderWidthDp, { v -> vm.setPipParams(clip.pip.scale, clip.pip.offsetXFrac, clip.pip.offsetYFrac, clip.pip.cornerRadiusDp, v) }, valueRange = 0f..8f, format = { "${it.roundToInt()}dp" })
    }
}

@Composable
private fun CaptionsPanel(
    doc: ProjectDoc,
    playhead: Long,
    vm: EditorViewModel,
    onImportSrt: () -> Unit,
    onExportSrt: () -> Unit
) {
    Column {
        val count = doc.textClips().size
        Text(
            "$count text / subtitle clips on the timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = CutColors.TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Import SRT", onClick = onImportSrt, icon = Icons.Rounded.Download)
            GhostButton("Export SRT", onClick = onExportSrt, icon = Icons.Rounded.Save)
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("Add caption at playhead", onClick = { vm.addText() }, icon = Icons.Rounded.Add)
        Spacer(Modifier.height(8.dp))
        Text(
            "Imported cues become real text clips, so they can be restyled and are exported with the video.",
            style = MaterialTheme.typography.bodySmall,
            color = CutColors.TextTertiary
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = CutColors.TextTertiary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = CutColors.TextPrimary)
    }
}
