package com.cutvideo.editor.ui.screens.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.media.VoiceRecorder
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.components.SectionHeader
import com.cutvideo.editor.ui.components.SegmentedControl
import com.cutvideo.editor.ui.components.ToggleRow
import com.cutvideo.editor.ui.components.ValueSlider
import com.cutvideo.editor.ui.components.percentFormat
import com.cutvideo.editor.ui.screens.editor.SessionViewModel
import com.cutvideo.editor.ui.theme.CutColors
import kotlinx.coroutines.delay

/**
 * Audio studio: real waveform peaks (decoded with MediaCodec, bucketed by the native engine), volume and
 * fade automation, audio extraction, music import and voice-over recording.
 */
@Composable
fun AudioEditorScreen(
    vm: SessionViewModel,
    projectId: String,
    onBack: () -> Unit,
    onPickMusic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val doc by vm.doc.collectAsStateWithLifecycle()
    val selectedId by vm.selectedClipId.collectAsStateWithLifecycle()
    val playhead by vm.playheadUs.collectAsStateWithLifecycle()
    val peaks by vm.waveform.collectAsStateWithLifecycle()
    val peaksLoading by vm.waveformLoading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordStart by remember { mutableStateOf(0L) }
    var elapsedUs by remember { mutableStateOf(0L) }
    var mode by remember { mutableStateOf(0) } // 0 = clip, 1 = record

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recorder.start()
            recording = true
            recordStart = System.currentTimeMillis()
        } else {
            vm.notify("Microphone permission is required to record a voice-over")
        }
    }

    val musicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importMedia(uris, TrackType.AUDIO) { vm.notify("${uris.size} audio file(s) added") }
    }

    LaunchedEffect(projectId) { vm.open(projectId) }

    LaunchedEffect(recording) {
        while (recording) {
            elapsedUs = (System.currentTimeMillis() - recordStart) * 1000L
            delay(120)
        }
    }

    val document = doc
    val audioClips = document?.audioClips()?.sortedBy { it.startUs } ?: emptyList()
    val videoClips = document?.videoClips()?.sortedBy { it.startUs } ?: emptyList()
    val editable = audioClips + videoClips
    val selected = document?.clip(selectedId ?: "") ?: audioClips.firstOrNull() ?: videoClips.firstOrNull()

    LaunchedEffect(selected?.uri) {
        selected?.uri?.takeIf { it.isNotBlank() }?.let { vm.loadWaveform(it) }
    }

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
                Text("Audio", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
                Text(
                    "${audioClips.size} audio clips • master ${percentFormat(document?.masterVolume ?: 1f)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(CutColors.Surface).clickable(onClick = onPickMusic),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.MusicNote, "Add music", tint = CutColors.Accent, modifier = Modifier.size(18.dp))
            }
        }

        SegmentedControl(
            options = listOf("Clip audio", "Voice-over"),
            selectedIndex = mode,
            onSelect = { mode = it },
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(Modifier.height(10.dp))

        if (mode == 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(CutColors.BackgroundElevated)
                    .border(1.dp, CutColors.Border, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        null,
                        tint = if (recording) CutColors.Danger else CutColors.Accent,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (recording) TimeText.clock(elapsedUs) else "Voice-over recorder",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CutColors.TextPrimary
                    )
                    Text(
                        if (recording) "Recording from the microphone…" else "Tap record, speak, then stop to drop the take on the audio track at the playhead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CutColors.TextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    GradientButton(
                        text = if (recording) "Stop & add to timeline" else "Record",
                        icon = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        onClick = {
                            if (recording) {
                                val file = recorder.stop()
                                recording = false
                                elapsedUs = 0L
                                if (file != null) {
                                    val contentUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    vm.importMedia(listOf(contentUri), TrackType.AUDIO) {
                                        vm.notify("Voice-over added (${TimeText.clock((System.currentTimeMillis() - recordStart) * 1000L)})")
                                    }
                                } else {
                                    vm.notify("Recording failed — check that the microphone is available")
                                }
                            } else if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                recorder.start()
                                recording = true
                                recordStart = System.currentTimeMillis()
                            } else {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Takes are AAC 48 kHz mono, stored in the app cache and imported as normal clips.",
                        style = MaterialTheme.typography.labelSmall,
                        color = CutColors.TextTertiary
                    )
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
                Text("CLIPS", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(editable, key = { it.id }) { clip ->
                        AudioChip(
                            clip = clip,
                            selected = clip.id == selected?.id,
                            onClick = { vm.select(clip.id) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (selected == null) {
                    Text(
                        "No audio or video clips yet. Import media from the editor or add music with the button above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CutColors.TextSecondary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(CutColors.BackgroundSunken)
                            .border(1.dp, CutColors.Border, RoundedCornerShape(16.dp))
                    ) {
                        if (peaksLoading && peaks.isEmpty()) {
                            CircularProgressIndicator(
                                color = CutColors.Accent,
                                modifier = Modifier.align(Alignment.Center).size(24.dp)
                            )
                        } else if (peaks.isEmpty()) {
                            Text(
                                if (settings.showWaveforms) {
                                    "Waveform unavailable for this clip (no decodable audio track)"
                                } else {
                                    "Waveforms are disabled in Settings"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = CutColors.TextTertiary,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp)
                            )
                        } else {
                            Waveform(
                                peaks = peaks,
                                durationUs = selected.durationUs,
                                positionUs = (playhead - selected.startUs).coerceIn(0L, selected.durationUs),
                                fadeInUs = selected.fadeInUs,
                                fadeOutUs = selected.fadeOutUs,
                                muted = selected.muted,
                                modifier = Modifier.fillMaxSize().padding(8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selected.name.take(28),
                            style = MaterialTheme.typography.titleMedium,
                            color = CutColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${TimeText.clock(selected.startUs)} · ${TimeText.clock(selected.durationUs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CutColors.TextTertiary
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    ValueSlider(
                        label = "Volume",
                        value = selected.volume,
                        valueRange = 0f..2f,
                        defaultValue = 1f,
                        format = { percentFormat(it) + "%" },
                        onValueChange = { vm.setVolume(selected.id, it) }
                    )
                    ValueSlider(
                        label = "Fade in",
                        value = selected.fadeInUs / 1_000_000f,
                        valueRange = 0f..5f,
                        defaultValue = 0f,
                        format = { "%.1f s".format(it) },
                        onValueChange = {
                            vm.setFades(selected.id, (it * 1_000_000L).toLong(), selected.fadeOutUs)
                        }
                    )
                    ValueSlider(
                        label = "Fade out",
                        value = selected.fadeOutUs / 1_000_000f,
                        valueRange = 0f..5f,
                        defaultValue = 0f,
                        format = { "%.1f s".format(it) },
                        onValueChange = {
                            vm.setFades(selected.id, selected.fadeInUs, (it * 1_000_000L).toLong())
                        }
                    )

                    ToggleRow(
                        title = "Mute this clip",
                        checked = selected.muted,
                        onCheckedChange = { vm.toggleMute(selected.id) }
                    )
                    ToggleRow(
                        title = "Mute original video audio",
                        subtitle = "Keep only the music on the video track",
                        checked = vm.isOriginalMuted,
                        onCheckedChange = { vm.toggleMuteOriginal() }
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected.hasVideoStream) {
                            GhostButton(text = "Extract audio", icon = Icons.Rounded.Audiotrack, onClick = {
                                vm.extractAudio(selected.id)
                            })
                        }
                        GhostButton(text = "Add music", icon = Icons.Rounded.MusicNote, onClick = {
                            musicLauncher.launch(arrayOf("audio/*"))
                        })
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionHeader(title = "Tracks", action = "${document?.tracks?.size ?: 0}")
                Spacer(Modifier.height(6.dp))
                document?.tracks?.forEach { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CutColors.Surface.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (track.type == TrackType.AUDIO) Icons.Rounded.Audiotrack else Icons.Rounded.VolumeUp,
                            null,
                            tint = CutColors.trackColor(track.type.name),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CutColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${track.clips.size} clips • ${(track.volume * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = CutColors.TextTertiary
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (track.muted) CutColors.Danger.copy(alpha = 0.2f) else CutColors.Surface)
                                .clickable { vm.notify("Track mute is edited from the timeline long-press menu") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (track.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                "Mute track",
                                tint = if (track.muted) CutColors.Danger else CutColors.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                ValueSlider(
                    label = "Master volume",
                    value = document?.masterVolume ?: 1f,
                    valueRange = 0f..1.5f,
                    defaultValue = 1f,
                    format = { percentFormat(it) + "%" },
                    onValueChange = { value ->
                        vm.editor.mutateLive { it.copy(masterVolume = value) }
                    }
                )
                Spacer(Modifier.height(18.dp))
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
private fun Waveform(
    peaks: List<Float>,
    durationUs: Long,
    positionUs: Long,
    fadeInUs: Long,
    fadeOutUs: Long,
    muted: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = if (durationUs > 0) (positionUs.toFloat() / durationUs).coerceIn(0f, 1f) else 0f
    Canvas(modifier) {
        if (peaks.isEmpty()) return@Canvas
        val barWidth = size.width / peaks.size
        val centerY = size.height / 2f
        peaks.forEachIndexed { index, peak ->
            val h = (peak.coerceIn(0f, 1f) * size.height * 0.9f).coerceAtLeast(1.5f)
            val x = index * barWidth
            val played = x <= size.width * progress
            drawRect(
                color = when {
                    muted -> CutColors.TextTertiary.copy(alpha = 0.4f)
                    played -> CutColors.Accent
                    else -> CutColors.AccentSecondary.copy(alpha = 0.45f)
                },
                topLeft = Offset(x + barWidth * 0.15f, centerY - h / 2f),
                size = Size(barWidth * 0.7f, h)
            )
        }
        if (fadeInUs > 0 && durationUs > 0) {
            val fadeWidth = size.width * (fadeInUs.toFloat() / durationUs).coerceIn(0f, 1f)
            drawRect(
                color = CutColors.Success.copy(alpha = 0.25f),
                topLeft = Offset.Zero,
                size = Size(fadeWidth, size.height)
            )
        }
        if (fadeOutUs > 0 && durationUs > 0) {
            val fadeWidth = size.width * (fadeOutUs.toFloat() / durationUs).coerceIn(0f, 1f)
            drawRect(
                color = CutColors.Warning.copy(alpha = 0.25f),
                topLeft = Offset(size.width - fadeWidth, 0f),
                size = Size(fadeWidth, size.height)
            )
        }
        drawLine(
            color = Color.White,
            start = Offset(size.width * progress, 0f),
            end = Offset(size.width * progress, size.height),
            strokeWidth = 2f
        )
    }
}

@Composable
private fun AudioChip(clip: Clip, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) CutColors.AccentSoft else CutColors.Surface.copy(alpha = 0.6f))
            .border(
                1.dp,
                if (selected) CutColors.Accent else CutColors.Border,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Text(
            clip.name.take(20),
            style = MaterialTheme.typography.bodyMedium,
            color = CutColors.TextPrimary,
            maxLines = 1
        )
        Text(
            "${TimeText.clock(clip.startUs)} · ${TimeText.clock(clip.durationUs)}",
            style = MaterialTheme.typography.labelSmall,
            color = CutColors.TextTertiary
        )
    }
}
