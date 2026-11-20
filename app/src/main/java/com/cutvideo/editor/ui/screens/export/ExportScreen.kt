package com.cutvideo.editor.ui.screens.export

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.domain.export.ExportFrameRate
import com.cutvideo.editor.domain.export.ExportResolution
import com.cutvideo.editor.domain.export.ExportState
import com.cutvideo.editor.domain.export.VideoCodec
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.components.LabeledValue
import com.cutvideo.editor.ui.components.SectionHeader
import com.cutvideo.editor.ui.components.StatusBadge
import com.cutvideo.editor.ui.theme.CutColors

/**
 * Real export screen. Everything shown here comes from the Media3 Transformer run: progress is the
 * transformer's own progress, the ETA is measured, and the result is published to the gallery
 * (MediaStore) before it is reported as success.
 */
@Composable
fun ExportScreen(
    vm: ExportViewModel,
    projectId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val doc by vm.doc.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val warnings by vm.warnings.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val frameRate by vm.frameRate.collectAsStateWithLifecycle()
    val codec by vm.codec.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LaunchedEffect(projectId) {
        vm.seedFrom(settings)
        vm.open(projectId)
    }
    LaunchedEffect(doc?.durationUs, resolution, frameRate, codec) {
        if (doc != null) vm.refreshNotes()
    }

    val document = doc
    val running = state is ExportState.Preparing || state is ExportState.Running

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
            Column(Modifier.weight(1f)) {
                Text("Export", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
                Text(
                    document?.name ?: "Loading project…",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
            StatusBadge("Media3", CutColors.Accent)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            if (document == null) {
                Text(
                    "No project loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CutColors.TextSecondary,
                    modifier = Modifier.padding(20.dp)
                )
                return@Column
            }

            // Summary
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CutColors.BackgroundElevated)
                    .border(1.dp, CutColors.Border, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                val (w, h) = resolution.sizeFor(document.aspect)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Movie, null, tint = CutColors.Accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$w × $h • ${frameRate.label} • ${codec.label}",
                            style = MaterialTheme.typography.titleMedium,
                            color = CutColors.TextPrimary
                        )
                        Text(
                            "${document.aspect.label} • ${TimeText.clock(document.durationUs)} • " +
                                "${document.clipCount()} clips",
                            style = MaterialTheme.typography.bodySmall,
                            color = CutColors.TextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    LabeledValue("Est. size", vm.estimatedSizeLabel(), modifier = Modifier.weight(1f))
                    LabeledValue("Bitrate", "${vm.currentSettings().bitrate(document.aspect) / 1_000_000} Mbps", modifier = Modifier.weight(1f))
                    LabeledValue("Audio", "AAC 128k", modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "Resolution", action = resolution.label)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExportResolution.entries.toList()) { option ->
                    OptionChip(
                        label = option.label,
                        selected = option == resolution,
                        enabled = !running,
                        onClick = { vm.setResolution(option) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "Frame rate", action = frameRate.label)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExportFrameRate.entries.toList()) { option ->
                    OptionChip(
                        label = option.label,
                        selected = option == frameRate,
                        enabled = !running,
                        onClick = { vm.setFrameRate(option) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "Codec", action = codec.label)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(VideoCodec.entries.toList()) { option ->
                    OptionChip(
                        label = option.label,
                        selected = option == codec,
                        enabled = !running,
                        onClick = { vm.setCodec(option) }
                    )
                }
            }

            if (notes.isNotEmpty() || warnings.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionHeader(title = "Notes", action = "${notes.size + warnings.size}")
                Spacer(Modifier.height(6.dp))
                (notes + warnings).distinct().forEach { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CutColors.Warning.copy(alpha = 0.12f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            null,
                            tint = CutColors.Warning,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = CutColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when (val current = state) {
                is ExportState.Running, is ExportState.Preparing -> {
                    val progress = (current as? ExportState.Running)?.progress ?: 0f
                    val animated by animateFloatAsState(progress, label = "exportProgress")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(CutColors.BackgroundElevated)
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.displaySmall,
                                color = CutColors.TextPrimary
                            )
                            Spacer(Modifier.weight(1f))
                            (current as? ExportState.Running)?.frameRate?.let { fps ->
                                StatusBadge("%.1f fps".format(fps), CutColors.Info)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(CutColors.Surface)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(animated)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(CutColors.accentGradientHorizontal)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            (current as? ExportState.Running)?.stage ?: "Preparing media…",
                            style = MaterialTheme.typography.bodySmall,
                            color = CutColors.TextSecondary
                        )
                        (current as? ExportState.Running)?.estimatedRemainingMs?.let { eta ->
                            Text(
                                "~${TimeText.clock(eta * 1000L)} remaining",
                                style = MaterialTheme.typography.labelSmall,
                                color = CutColors.TextTertiary
                            )
                        }
                    }
                }

                is ExportState.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(CutColors.Success.copy(alpha = 0.12f))
                            .border(1.dp, CutColors.Success.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = CutColors.Success)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Exported to your gallery",
                                style = MaterialTheme.typography.titleMedium,
                                color = CutColors.TextPrimary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${TimeText.bytes(current.sizeBytes)} • ${current.outputPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CutColors.TextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GradientButton(
                                text = "Share",
                                icon = Icons.Rounded.Share,
                                height = 44.dp,
                                onClick = { shareExported(context, current.contentUri, document.name) }
                            )
                            GhostButton(text = "Done", onClick = onDone)
                        }
                    }
                }

                is ExportState.Failed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(CutColors.Danger.copy(alpha = 0.12f))
                            .border(1.dp, CutColors.Danger.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text("Export failed", style = MaterialTheme.typography.titleMedium, color = CutColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(current.message, style = MaterialTheme.typography.bodySmall, color = CutColors.TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        GhostButton(text = "Try again", onClick = { vm.reset() })
                    }
                }

                is ExportState.Cancelled -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(CutColors.Surface)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Export cancelled", color = CutColors.TextSecondary)
                        Spacer(Modifier.weight(1f))
                        GhostButton(text = "Start over", onClick = { vm.reset() })
                    }
                }

                ExportState.Idle -> Unit
            }

            Spacer(Modifier.height(20.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CutColors.BackgroundElevated)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) {
                GhostButton(text = "Cancel", icon = Icons.Rounded.Close, onClick = { vm.cancel() })
                Spacer(Modifier.weight(1f))
                Text(
                    "Rendering…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CutColors.TextSecondary
                )
            } else {
                Text(
                    "Exports keep the full timeline: cuts, speed, filters, effects, text, audio and fades.",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                GradientButton(
                    text = "Export video",
                    height = 46.dp,
                    onClick = {
                        vm.reset()
                        vm.start()
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> CutColors.TextTertiary
            selected -> Color.White
            else -> CutColors.TextSecondary
        },
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CutColors.Accent else CutColors.Surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

private fun shareExported(context: android.content.Context, contentUri: String?, name: String) {
    val uri = contentUri?.let { Uri.parse(it) } ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${name}.mp4"))
}
