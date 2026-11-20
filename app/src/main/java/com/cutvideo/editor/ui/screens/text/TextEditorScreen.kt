package com.cutvideo.editor.ui.screens.text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.domain.model.AnimationCatalog
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.FontCatalog
import com.cutvideo.editor.domain.model.TextAlign
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.ui.components.ColorPalette
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.components.SegmentedControl
import com.cutvideo.editor.ui.components.SectionHeader
import com.cutvideo.editor.ui.components.TextPreviewThumb
import com.cutvideo.editor.ui.components.ToggleRow
import com.cutvideo.editor.ui.components.ValueSlider
import com.cutvideo.editor.ui.components.percentFormat
import com.cutvideo.editor.ui.screens.editor.SessionViewModel
import com.cutvideo.editor.ui.theme.CutColors
import com.cutvideo.editor.ui.theme.FontFamilies

/**
 * Dedicated text & caption studio. Every control writes straight into the shared document, and the
 * preview at the top renders the same [TextSpec] the export rasteriser uses.
 */
@Composable
fun TextEditorScreen(
    vm: SessionViewModel,
    projectId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val selectedId by vm.selectedClipId.collectAsStateWithLifecycle()
    val playhead by vm.playheadUs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importSubtitles(it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri -> uri?.let { vm.exportSubtitles(it) } }

    LaunchedEffect(projectId) {
        vm.open(projectId)
    }

    val document = doc
    val textClips = document?.textClips()?.sortedBy { it.startUs } ?: emptyList()
    val selected = document?.clip(selectedId ?: "")?.takeIf { it.isText } ?: textClips.firstOrNull()
    val spec = selected?.text ?: TextSpec()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CutColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIcon(Icons.Rounded.ArrowBack, onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Text & captions", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
                Text(
                    "${textClips.size} text clips • ${TimeText.clock(document?.durationUs ?: 0L)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
            CircleIcon(Icons.Rounded.Save) { vm.saveNow() }
        }

        // ---- preview ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(CutColors.BackgroundSunken)
                .height(210.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(document?.aspect?.ratio ?: 0.5625f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = spec.text.ifBlank { "Your text here" },
                    color = Color(spec.color),
                    fontFamily = FontFamilies.composeFamily(spec.fontId),
                    fontWeight = if (spec.bold) FontWeight.Bold else FontWeight.Normal,
                    fontSize = (spec.sizeSp * 0.42f).sp,
                    lineHeight = (spec.sizeSp * 0.42f * spec.lineHeightMultiplier).sp,
                    letterSpacing = spec.letterSpacing.sp,
                    textAlign = when (spec.align) {
                        TextAlign.LEFT -> ComposeTextAlign.Left
                        TextAlign.CENTER -> ComposeTextAlign.Center
                        TextAlign.RIGHT -> ComposeTextAlign.Right
                    },
                    textDecoration = if (spec.underline) {
                        androidx.compose.ui.text.style.TextDecoration.Underline
                    } else {
                        androidx.compose.ui.text.style.TextDecoration.None
                    },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- layers ----
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                Column(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CutColors.AccentSoft)
                        .border(1.dp, CutColors.Accent, RoundedCornerShape(14.dp))
                        .clickable {
                            val id = vm.addText(playhead)
                            id?.let { vm.select(it) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Add, "Add text", tint = CutColors.Accent, modifier = Modifier.size(20.dp))
                    Text("Add", style = MaterialTheme.typography.labelSmall, color = CutColors.Accent)
                }
            }
            items(textClips, key = { it.id }) { clip ->
                TextLayerChip(
                    clip = clip,
                    selected = clip.id == selected?.id,
                    onClick = { vm.select(clip.id) },
                    onDelete = {
                        vm.deleteClip(clip.id)
                        vm.notify("Text clip deleted")
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (selected == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No text clips yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = CutColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add a title at the playhead, or import a .srt subtitle file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CutColors.TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(text = "Add text", icon = Icons.Rounded.Add, height = 44.dp, onClick = {
                            val id = vm.addText(playhead)
                            id?.let { vm.select(it) }
                        })
                        GhostButton(text = "Import SRT", icon = Icons.Rounded.Upload, onClick = {
                            importLauncher.launch(arrayOf("application/x-subrip", "text/plain", "*/*"))
                        })
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                OutlinedTextField(
                    value = spec.text,
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text") },
                    minLines = 2,
                    maxLines = 4
                )

                Spacer(Modifier.height(10.dp))
                Text("FONT", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(FontCatalog.fonts) { (id, label) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamilies.composeFamily(id),
                            color = if (spec.fontId == id) Color.White else CutColors.TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (spec.fontId == id) CutColors.Accent else CutColors.Surface)
                                .clickable {
                                    vm.updateTextClip(selected.id, spec.copy(fontId = id))
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                ValueSlider(
                    label = "Size",
                    value = spec.sizeSp,
                    valueRange = 12f..140f,
                    defaultValue = 36f,
                    format = { "${it.toInt()} sp" },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(sizeSp = it)) }
                )
                ValueSlider(
                    label = "Letter spacing",
                    value = spec.letterSpacing,
                    valueRange = -2f..12f,
                    defaultValue = 0f,
                    format = { "%.1f".format(it) },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(letterSpacing = it)) }
                )
                ValueSlider(
                    label = "Line height",
                    value = spec.lineHeightMultiplier,
                    valueRange = 0.8f..2.2f,
                    defaultValue = 1.15f,
                    format = { "%.2f".format(it) },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(lineHeightMultiplier = it)) }
                )
                ValueSlider(
                    label = "Horizontal position",
                    value = spec.xFrac,
                    valueRange = 0f..1f,
                    defaultValue = 0.5f,
                    format = { percentFormat(it) + "%" },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(xFrac = it)) }
                )
                ValueSlider(
                    label = "Vertical position",
                    value = spec.yFrac,
                    valueRange = 0f..1f,
                    defaultValue = 0.78f,
                    format = { percentFormat(it) + "%" },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(yFrac = it)) }
                )
                ValueSlider(
                    label = "Max width",
                    value = spec.maxWidthFrac,
                    valueRange = 0.2f..1f,
                    defaultValue = 0.86f,
                    format = { percentFormat(it) + "%" },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(maxWidthFrac = it)) }
                )
                ValueSlider(
                    label = "Rotation",
                    value = spec.rotationDegrees,
                    valueRange = -45f..45f,
                    defaultValue = 0f,
                    format = { "${it.toInt()}°" },
                    onValueChange = { vm.updateTextClip(selected.id, spec.copy(rotationDegrees = it)) }
                )

                Spacer(Modifier.height(10.dp))
                Text("ALIGNMENT", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = listOf("Left", "Center", "Right"),
                    selectedIndex = when (spec.align) {
                        TextAlign.LEFT -> 0
                        TextAlign.CENTER -> 1
                        TextAlign.RIGHT -> 2
                    },
                    onSelect = { index ->
                        vm.updateTextClip(
                            selected.id,
                            spec.copy(
                                align = when (index) {
                                    0 -> TextAlign.LEFT
                                    1 -> TextAlign.CENTER
                                    else -> TextAlign.RIGHT
                                }
                            )
                        )
                    }
                )

                Spacer(Modifier.height(10.dp))
                Text("TEXT COLOR", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                ColorPalette(
                    colors = palette,
                    selected = Color(spec.color),
                    onSelect = { vm.updateTextClip(selected.id, spec.copy(color = it.toArgb())) }
                )

                Spacer(Modifier.height(10.dp))
                Text("BACKGROUND", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                ColorPalette(
                    colors = listOf(Color.Transparent) + palette,
                    selected = Color(spec.backgroundColor),
                    onSelect = { vm.updateTextClip(selected.id, spec.copy(backgroundColor = it.toArgb())) }
                )
                if (spec.backgroundColor != 0) {
                    ValueSlider(
                        label = "Background padding",
                        value = spec.backgroundPaddingDp,
                        valueRange = 0f..32f,
                        defaultValue = 8f,
                        format = { "${it.toInt()} dp" },
                        onValueChange = { vm.updateTextClip(selected.id, spec.copy(backgroundPaddingDp = it)) }
                    )
                }

                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Bold",
                    checked = spec.bold,
                    onCheckedChange = { vm.updateTextClip(selected.id, spec.copy(bold = it)) }
                )
                ToggleRow(
                    title = "Italic",
                    checked = spec.italic,
                    onCheckedChange = { vm.updateTextClip(selected.id, spec.copy(italic = it)) }
                )
                ToggleRow(
                    title = "Underline",
                    checked = spec.underline,
                    onCheckedChange = { vm.updateTextClip(selected.id, spec.copy(underline = it)) }
                )
                ToggleRow(
                    title = "Drop shadow",
                    subtitle = "Soft shadow keeps captions readable on bright footage",
                    checked = spec.shadow.enabled,
                    onCheckedChange = {
                        vm.updateTextClip(selected.id, spec.copy(shadow = spec.shadow.copy(enabled = it)))
                    }
                )
                if (spec.shadow.enabled) {
                    ValueSlider(
                        label = "Shadow blur",
                        value = spec.shadow.radius,
                        valueRange = 0f..32f,
                        defaultValue = 8f,
                        format = { "${it.toInt()} px" },
                        onValueChange = {
                            vm.updateTextClip(selected.id, spec.copy(shadow = spec.shadow.copy(radius = it)))
                        }
                    )
                }
                ToggleRow(
                    title = "Outline",
                    subtitle = "Solid stroke around every glyph",
                    checked = spec.outline.enabled,
                    onCheckedChange = {
                        vm.updateTextClip(selected.id, spec.copy(outline = spec.outline.copy(enabled = it)))
                    }
                )
                if (spec.outline.enabled) {
                    ValueSlider(
                        label = "Outline width",
                        value = spec.outline.width,
                        valueRange = 0.5f..12f,
                        defaultValue = 4f,
                        format = { "%.1f px".format(it) },
                        onValueChange = {
                            vm.updateTextClip(selected.id, spec.copy(outline = spec.outline.copy(width = it)))
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                SectionHeader(title = "Animation", action = "Applies to this clip")
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AnimationCatalog.presets) { preset ->
                        val selectedAnimation = selected.animationIn.type == preset.type
                        Text(
                            preset.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedAnimation) Color.White else CutColors.TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedAnimation) preset.accent else CutColors.Surface)
                                .clickable {
                                    vm.setAnimation(
                                        selected.id,
                                        preset.type,
                                        selected.animationOut.type,
                                        selected.animationIn.durationUs.coerceAtLeast(400_000L)
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("TIMING", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${TimeText.clock(selected.startUs)} → ${TimeText.clock(selected.endUs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CutColors.TextSecondary
                )
                ValueSlider(
                    label = "Duration",
                    value = selected.durationUs / 1_000_000f,
                    valueRange = 0.5f..30f,
                    defaultValue = 3f,
                    format = { "%.1f s".format(it) },
                    onValueChange = {
                        vm.updateClipLive(selected.id) { clip ->
                            clip.copy(durationUs = (it * 1_000_000L).toLong().coerceAtLeast(300_000L))
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(text = "Import SRT", icon = Icons.Rounded.Upload, onClick = {
                        importLauncher.launch(arrayOf("application/x-subrip", "text/plain", "*/*"))
                    })
                    GhostButton(text = "Export SRT", icon = Icons.Rounded.Download, onClick = {
                        exportLauncher.launch("cutvideo-subtitles.srt")
                    })
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        if (message != null) {
            Text(
                message!!,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CutColors.SurfaceHighest)
                    .padding(10.dp)
                    .clickable { vm.dismissMessage() }
            )
        }
    }
}

@Composable
private fun TextLayerChip(
    clip: Clip,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
        TextPreviewThumb(spec = clip.text ?: TextSpec(), selected = selected, modifier = Modifier.clickable(onClick = onClick))
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(CutColors.Danger)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Delete, "Delete", tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun CircleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(CutColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = CutColors.TextPrimary, modifier = Modifier.size(18.dp))
    }
}

private val palette = listOf(
    Color.White,
    Color(0xFFF4F4F5),
    Color(0xFFFFD166),
    Color(0xFFF97316),
    Color(0xFFEF4444),
    Color(0xFFEC4899),
    Color(0xFFA855F7),
    Color(0xFF6366F1),
    Color(0xFF3B82F6),
    Color(0xFF22C55E),
    Color(0xFF14B8A6),
    Color.Black
)
