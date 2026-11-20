package com.cutvideo.editor.ui.screens.settings

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.data.repository.PreviewQuality
import com.cutvideo.editor.domain.export.ExportFrameRate
import com.cutvideo.editor.domain.export.ExportResolution
import com.cutvideo.editor.domain.export.VideoCodec
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.LabeledValue
import com.cutvideo.editor.ui.components.SectionHeader
import com.cutvideo.editor.ui.components.SegmentedControl
import com.cutvideo.editor.ui.components.StatusBadge
import com.cutvideo.editor.ui.components.ToggleRow
import com.cutvideo.editor.ui.components.ValueSlider
import com.cutvideo.editor.ui.theme.CutColors

/** Settings backed by DataStore — every switch changes behaviour immediately. */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenExported: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val exportCount by vm.exportCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

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
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(CutColors.Surface).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = CutColors.TextPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            StatusBadge("v1.0", CutColors.Accent)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            SectionHeader(title = "Preview")
            Spacer(Modifier.height(6.dp))
            Text(
                "Decode quality of the editing preview. Higher quality uses more CPU/battery.",
                style = MaterialTheme.typography.bodySmall,
                color = CutColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = PreviewQuality.entries.map { it.label },
                selectedIndex = PreviewQuality.entries.indexOf(settings.previewQuality),
                onSelect = { vm.setPreviewQuality(PreviewQuality.entries[it]) }
            )

            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "GPU effects preview",
                subtitle = "Run filters and effects through the OpenGL ES shader",
                checked = settings.hardwareEffects,
                onCheckedChange = { vm.setHardwareEffects(it) }
            )
            ToggleRow(
                title = "Snapping",
                subtitle = "Clips snap to neighbours and the playhead",
                checked = settings.snappingEnabled,
                onCheckedChange = { vm.setSnapping(it) }
            )
            ToggleRow(
                title = "Haptic feedback",
                subtitle = "Vibrate on trim and split",
                checked = settings.hapticsEnabled,
                onCheckedChange = { vm.setHaptics(it) }
            )
            ToggleRow(
                title = "Waveforms",
                subtitle = "Decode audio peaks in the audio editor",
                checked = settings.showWaveforms,
                onCheckedChange = { vm.setWaveforms(it) }
            )

            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "Defaults")
            Spacer(Modifier.height(6.dp))
            Text("New project aspect", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AspectRatio.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { option ->
                            Chip(
                                label = option.label,
                                selected = settings.defaultAspect == option,
                                onClick = { vm.setDefaultAspect(option) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Default export resolution", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportResolution.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { option ->
                            Chip(
                                label = option.label,
                                selected = settings.exportResolution == option,
                                onClick = { vm.setExportResolution(option) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Default frame rate", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportFrameRate.entries.forEach { option ->
                    Chip(
                        label = option.label,
                        selected = settings.exportFrameRate == option,
                        onClick = { vm.setExportFrameRate(option) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Default codec", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoCodec.entries.forEach { option ->
                    Chip(
                        label = option.label,
                        selected = settings.exportCodec == option,
                        onClick = { vm.setExportCodec(option) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            ValueSlider(
                label = "Autosave interval",
                value = settings.autosaveSeconds.toFloat(),
                valueRange = 2f..30f,
                defaultValue = 8f,
                format = { "${it.toInt()} s" },
                onValueChange = { vm.setAutosaveSeconds(it.toInt().coerceIn(2, 30)) }
            )

            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "Storage")
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CutColors.BackgroundElevated)
                    .border(1.dp, CutColors.Border, RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Row {
                    LabeledValue("Thumbnail cache", TimeText.bytes(cacheBytes), modifier = Modifier.weight(1f))
                    LabeledValue("Exports", "$exportCount", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(text = "Clear cache", icon = Icons.Rounded.DeleteSweep, onClick = { vm.clearCache() })
                    GhostButton(text = "Exported videos", icon = Icons.Rounded.Movie, onClick = onOpenExported)
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "Diagnostics")
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CutColors.BackgroundElevated)
                    .border(1.dp, CutColors.Border, RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Memory,
                        null,
                        tint = if (vm.nativeAvailable) CutColors.Success else CutColors.Warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (vm.nativeAvailable) "Native engine loaded — ${vm.nativeVersion}" else "Native engine unavailable (Kotlin fallback)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CutColors.TextPrimary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    vm.nativeDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "About")
            Spacer(Modifier.height(6.dp))
            Text(
                "CutVideo 1.0 · Kotlin + Jetpack Compose + C++/NDK (JNI) + Media3 Transformer + OpenGL ES.\n" +
                    "Preview and export both read the same project document, so what you cut is what you get.",
                style = MaterialTheme.typography.bodySmall,
                color = CutColors.TextSecondary
            )
            Spacer(Modifier.height(24.dp))
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
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.White else CutColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CutColors.Accent else CutColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
