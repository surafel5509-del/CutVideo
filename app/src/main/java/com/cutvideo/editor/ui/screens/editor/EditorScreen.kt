package com.cutvideo.editor.ui.screens.editor

import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.timeline.TimelineManager
import com.cutvideo.editor.editor.AnimationEngine
import com.cutvideo.editor.ui.components.CutVideoLogo
import com.cutvideo.editor.ui.components.EditorTimeline
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.components.StatusBadge
import com.cutvideo.editor.ui.components.TransportBar
import com.cutvideo.editor.ui.theme.CutColors
import com.cutvideo.editor.nativebridge.NativeEngine
import com.cutvideo.editor.ui.theme.FontFamilies
import kotlin.math.min

/**
 * The editing workspace.
 *
 * Layout: header → live preview (with text/PiP compositing) → transport → tool rail → timeline →
 * contextual tool panel. All state comes from [EditorViewModel]; the preview is the real ExoPlayer
 * instance from the shared [com.cutvideo.editor.editor.PreviewController].
 */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    projectId: String,
    nativeEngine: NativeEngine,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onOpenTextEditor: () -> Unit,
    onOpenEffectsLibrary: () -> Unit,
    onOpenAudioEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val previewState by vm.previewState.collectAsStateWithLifecycle()
    val selectedClipId by vm.selectedClipId.collectAsStateWithLifecycle()
    val playhead by vm.playheadUs.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val zoom by vm.zoom.collectAsStateWithLifecycle()
    val snapping by vm.snapping.collectAsStateWithLifecycle()
    val fullscreen by vm.fullscreen.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val canUndo by vm.editor.undoRedo.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.editor.undoRedo.canRedo.collectAsStateWithLifecycle()

    var clipMenu by remember { mutableStateOf<Clip?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var mediaSheet by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { vm.open(projectId) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importMedia(uris, TrackType.VIDEO)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importMedia(uris, TrackType.AUDIO)
    }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importMedia(uris, TrackType.OVERLAY)
    }
    val srtImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importSubtitles(it) }
    }
    val srtExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri -> uri?.let { vm.exportSubtitles(it) } }

    val document = doc
    if (document == null) {
        Box(Modifier.fillMaxSize().background(CutColors.Background), contentAlignment = Alignment.Center) {
            CutVideoLogo(size = 64.dp)
        }
        return
    }

    val selectedClip = selectedClipId?.let { document.clip(it) }

    Box(Modifier.fillMaxSize().background(CutColors.Background)) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            EditorHeader(
                title = document.name,
                nativeEngineAvailable = nativeEngine.available,
                onBack = onBack,
                onRename = { renameOpen = true },
                onSave = { vm.saveNow() },
                onExport = onExport
            )

            // ---------------- preview ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (fullscreen) 2.2f else 1.15f)
                    .padding(horizontal = 10.dp)
            ) {
                PreviewStage(
                    doc = document,
                    vm = vm,
                    playhead = playhead,
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (fullscreen) {
                        HeaderIcon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.AspectRatio) {
                            vm.toggleFullscreen()
                        }
                    }
                    if (previewState.buffering) {
                        StatusBadge("buffering", CutColors.Info)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            TransportBar(
                isPlaying = previewState.isPlaying,
                positionUs = playhead,
                durationUs = document.durationUs,
                frameRate = document.frameRate,
                speed = previewState.speed,
                muted = previewState.muteOriginal,
                canUndo = canUndo,
                canRedo = canRedo,
                modifier = Modifier.padding(horizontal = 10.dp),
                onPlayPause = { vm.togglePlay() },
                onStepBack = { vm.stepFrame(-1) },
                onStepForward = { vm.stepFrame(1) },
                onUndo = { vm.undo() },
                onRedo = { vm.redo() },
                onToggleMute = { vm.toggleMuteOriginal() },
                onSpeedClick = { vm.selectTool(EditorTool.SPEED) },
                onFullscreen = { vm.toggleFullscreen() }
            )

            Spacer(Modifier.height(6.dp))

            ToolRail(
                selected = tool,
                onSelect = { toolSelection ->
                    if (toolSelection == EditorTool.TEXT) onOpenTextEditor() else vm.selectTool(toolSelection)
                },
                onAddMedia = { mediaSheet = true },
                modifier = Modifier.padding(horizontal = 10.dp)
            )

            Spacer(Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (fullscreen) 0.6f else 1f)
                    .padding(horizontal = 10.dp)
            ) {
                EditorTimeline(
                    doc = document,
                    playheadUs = playhead,
                    selectedClipId = selectedClipId,
                    zoom = zoom,
                    snapping = snapping,
                    modifier = Modifier.fillMaxSize(),
                    onSeek = { vm.seekTo(it) },
                    onSelect = { vm.selectClip(it) },
                    onMoveClip = { id, start, track, live -> vm.moveClip(id, start, track, live) },
                    onTrimStart = { id, us, live -> vm.trimStart(id, us, live) },
                    onTrimEnd = { id, us, live -> vm.trimEnd(id, us, live) },
                    onGestureFinished = { vm.gestureCheckpoint() },
                    onClipMenu = { clipMenu = it },
                    onZoomChange = { vm.setZoom(it) }
                )
            }

            AnimatedVisibility(
                visible = tool != EditorTool.NONE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(268.dp)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(CutColors.BackgroundElevated)
                        .border(
                            1.dp,
                            CutColors.Border,
                            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                        )
                ) {
                    EditorToolPanel(
                        tool = tool,
                        doc = document,
                        clip = selectedClip,
                        vm = vm,
                        nativeEngine = nativeEngine,
                        onOpenTextEditor = onOpenTextEditor,
                        onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                        onImportSrt = { srtImporter.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) },
                        onExportSrt = { srtExporter.launch("cutvideo-subtitles.srt") }
                    )
                }
            }
        }

        // Toast-style message
        AnimatedVisibility(
            visible = message != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = message ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(CutColors.SurfaceHighest.copy(alpha = 0.96f))
                    .border(1.dp, CutColors.Border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clickable { vm.dismissMessage() }
            )
        }
    }

    if (renameOpen) {
        RenameDialog(
            current = document.name,
            onDismiss = { renameOpen = false },
            onConfirm = { vm.renameProject(it); renameOpen = false }
        )
    }

    if (mediaSheet) {
        MediaSourceDialog(
            onDismiss = { mediaSheet = false },
            onPickVideos = { mediaSheet = false; videoPicker.launch(arrayOf("video/*")) },
            onPickAudio = { mediaSheet = false; audioPicker.launch(arrayOf("audio/*")) },
            onPickOverlay = { mediaSheet = false; overlayPicker.launch(arrayOf("video/*")) }
        )
    }

    clipMenu?.let { clip ->
        ClipContextDialog(
            clip = clip,
            onDismiss = { clipMenu = null },
            onSplit = { vm.split(); clipMenu = null },
            onDuplicate = { vm.duplicateSelected(); clipMenu = null },
            onDelete = { vm.deleteSelected(); clipMenu = null },
            onSpeed = { vm.selectTool(EditorTool.SPEED); clipMenu = null },
            onFilters = { vm.selectTool(EditorTool.FILTER); clipMenu = null },
            onEffects = { vm.selectTool(EditorTool.EFFECTS); clipMenu = null },
            onAnimation = { vm.selectTool(EditorTool.ANIMATION); clipMenu = null },
            onTransition = { vm.selectTool(EditorTool.TRANSITION); clipMenu = null },
            onCrop = { vm.selectTool(EditorTool.CROP); clipMenu = null },
            onAudio = { vm.selectTool(EditorTool.AUDIO); clipMenu = null },
            onChroma = { vm.selectTool(EditorTool.CHROMA); clipMenu = null },
            onPip = { vm.selectTool(EditorTool.PIP); clipMenu = null },
            onText = onOpenTextEditor,
            onFreeze = { vm.freezeFrame(); clipMenu = null },
            onExtractAudio = { vm.extractAudio(); clipMenu = null },
            onAddTrack = { vm.addTrack(it); clipMenu = null },
            onCopyStyle = { vm.copyStyle(); clipMenu = null },
            onPasteStyle = { vm.pasteStyle(); clipMenu = null },
            onApplyLookToAll = { vm.applyStyleToAll(); clipMenu = null },
            onAutoEnhance = { vm.autoEnhance(); clipMenu = null },
            onSendToOverlay = { vm.moveToOverlay(); clipMenu = null }
        )
    }
}

@Composable
private fun EditorHeader(
    title: String,
    nativeEngineAvailable: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIcon(Icons.Rounded.ArrowBack, onBack)
        CutVideoLogo(size = 26.dp, animate = false)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f).clickable(onClick = onRename)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = CutColors.TextPrimary,
                maxLines = 1
            )
            Row {
                Text("Tap to rename", style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
                if (!nativeEngineAvailable) {
                    Spacer(Modifier.width(6.dp))
                    StatusBadge("cpu", CutColors.Warning)
                }
            }
        }
        HeaderIcon(Icons.Rounded.Save, onSave)
        Spacer(Modifier.width(4.dp))
        GradientButton(
            text = "Export",
            onClick = onExport,
            height = 38.dp,
            icon = Icons.Rounded.Upload,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
        )
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CutColors.Surface.copy(alpha = 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = CutColors.TextPrimary, modifier = Modifier.size(19.dp))
    }
}

/**
 * Preview stage: ExoPlayer surface + optional PiP follower + Compose text layers, all driven by the
 * document and the playhead.
 */
@Composable
private fun PreviewStage(
    doc: ProjectDoc,
    vm: EditorViewModel,
    playhead: Long,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val activeClip = TimelineManager.primaryVideoClip(doc, playhead)
    val animation = activeClip?.let { AnimationEngine.compose(it, playhead) }
    val alpha by animateFloatAsState(targetValue = animation?.alpha ?: 1f, label = "previewAlpha")

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CutColors.BackgroundSunken),
        contentAlignment = Alignment.Center
    ) {
        val stageRatio = doc.aspect.ratio
        val stageWidth = maxWidth
        val stageHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(stageRatio)
                .clip(RoundedCornerShape(14.dp))
                .clipToBounds()
        ) {
            PlayerSurface(
                playerProvider = { vm.preview.player },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.alpha = alpha
                        val anim = animation
                        scaleX = anim?.scale ?: 1f
                        scaleY = anim?.scale ?: 1f
                        rotationZ = anim?.rotationDegrees ?: 0f
                        translationX = (anim?.translateX ?: 0f) * size.width
                        translationY = (anim?.translateY ?: 0f) * size.height
                    }
            )

            // Picture-in-picture follower surface
            val pipClip = doc.overlayClips().firstOrNull {
                it.pip.enabled && playhead >= it.startUs && playhead < it.endUs
            }
            if (pipClip != null) {
                val pipWidth = stageWidth * pipClip.pip.scale.coerceIn(0.15f, 0.95f)
                PlayerSurface(
                    playerProvider = { vm.preview.overlayPlayerInstance },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(pipWidth)
                        .aspectRatio(doc.aspect.ratio.coerceIn(0.5f, 2.4f))
                        .graphicsLayer {
                            translationX = pipClip.pip.offsetXFrac * stageWidth.toPx()
                            translationY = pipClip.pip.offsetYFrac * stageHeight.toPx()
                        }
                        .clip(RoundedCornerShape(pipClip.pip.cornerRadiusDp.dp))
                        .border(
                            pipClip.pip.borderWidthDp.dp,
                            Color(pipClip.pip.borderColor),
                            RoundedCornerShape(pipClip.pip.cornerRadiusDp.dp)
                        )
                )
            }

            // Text / caption layers
            doc.textClips().forEach { textClip ->
                if (playhead in textClip.startUs until textClip.endUs) {
                    val spec = textClip.text ?: return@forEach
                    val frame = AnimationEngine.compose(textClip, playhead)
                    val minSidePx = with(density) { min(stageWidth.toPx(), stageHeight.toPx()) }
                    val fontSizePx = spec.sizeSp * (minSidePx / 1080f)
                    val fontSize = with(density) { fontSizePx.toSp() }
                    Text(
                        text = spec.text,
                        color = Color(spec.color).copy(alpha = frame.alpha),
                        fontFamily = FontFamilies.composeFamily(spec.fontId),
                        fontWeight = if (spec.bold) FontWeight.Bold else FontWeight.Normal,
                        fontSize = fontSize,
                        lineHeight = fontSize * spec.lineHeightMultiplier,
                        textAlign = when (spec.align) {
                            com.cutvideo.editor.domain.model.TextAlign.LEFT -> androidx.compose.ui.text.style.TextAlign.Left
                            com.cutvideo.editor.domain.model.TextAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                            com.cutvideo.editor.domain.model.TextAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.Right
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(stageWidth * spec.maxWidthFrac)
                            .graphicsLayer {
                                translationX = (spec.xFrac - 0.5f) * stageWidth.toPx() + frame.translateX * size.width
                                translationY = (spec.yFrac - 0.5f) * stageHeight.toPx() + frame.translateY * size.height
                                scaleX = frame.scale
                                scaleY = frame.scale
                                rotationZ = spec.rotationDegrees + frame.rotationDegrees
                            }
                    )
                }
            }

            // Empty-project hint
            if (doc.videoClips().isEmpty()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, tint = CutColors.Accent, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add media to start editing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CutColors.TextSecondary
                        )
                    }
                }
            }

            PreviewSelectionOverlay(activeClip = activeClip, doc = doc, playhead = playhead)
        }
    }
}

@Composable
private fun PlayerSurface(
    playerProvider: () -> androidx.media3.exoplayer.ExoPlayer,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                player = playerProvider()
            }
        },
        update = { view ->
            if (view.player !== playerProvider()) {
                view.player = playerProvider()
            }
        }
    )
}

@Composable
private fun PreviewSelectionOverlay(activeClip: Clip?, doc: ProjectDoc, playhead: Long) {
    if (activeClip == null) return
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                activeClip.name.take(18),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            if (activeClip.filter.isActive) {
                Text(
                    com.cutvideo.editor.domain.model.FilterCatalog.preset(activeClip.filter.id).name,
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.Accent
                )
            }
        }
        Text(
            "${doc.aspect.label} • ${doc.frameRate}fps",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ToolRail(
    selected: EditorTool,
    onSelect: (EditorTool) -> Unit,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tools = listOf(
        EditorTool.TRIM to Icons.Rounded.CallSplit,
        EditorTool.SPLIT to Icons.Rounded.ContentCut,
        EditorTool.SPEED to Icons.Rounded.Speed,
        EditorTool.ADJUST to Icons.Rounded.Tune,
        EditorTool.FILTER to Icons.Rounded.FilterVintage,
        EditorTool.EFFECTS to Icons.Rounded.AutoFixHigh,
        EditorTool.ANIMATION to Icons.Rounded.Animation,
        EditorTool.TRANSITION to Icons.Rounded.BlurOn,
        EditorTool.CROP to Icons.Rounded.Crop,
        EditorTool.TEXT to Icons.Rounded.TextFields,
        EditorTool.AUDIO to Icons.Rounded.Audiotrack,
        EditorTool.CHROMA to Icons.Rounded.AutoFixHigh,
        EditorTool.PIP to Icons.Rounded.PictureInPictureAlt,
        EditorTool.CAPTIONS to Icons.Rounded.Subtitles
    )
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            ToolRailItem(
                icon = Icons.Rounded.AddPhotoAlternate,
                label = "Media",
                selected = false,
                accent = CutColors.Success,
                onClick = onAddMedia
            )
        }
        items(tools) { (tool, icon) ->
            ToolRailItem(
                icon = icon,
                label = tool.label,
                selected = selected == tool,
                onClick = { onSelect(tool) }
            )
        }
    }
}

@Composable
private fun ToolRailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = CutColors.Accent
) {
    Column(
        modifier = Modifier
            .width(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.2f) else CutColors.Surface.copy(alpha = 0.55f))
            .border(1.dp, if (selected) accent else CutColors.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Color.White else CutColors.TextSecondary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 8.5.sp,
            color = if (selected) Color.White else CutColors.TextTertiary,
            maxLines = 1
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CutColors.BackgroundElevated,
        title = { Text("Rename project", color = CutColors.TextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Project name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Save", color = CutColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CutColors.TextSecondary) } }
    )
}

private data class MediaSourceOption(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val action: () -> Unit
)

@Composable
private fun MediaSourceDialog(
    onDismiss: () -> Unit,
    onPickVideos: () -> Unit,
    onPickAudio: () -> Unit,
    onPickOverlay: () -> Unit
) {
    val options = listOf(
        MediaSourceOption(Icons.Rounded.Movie, "Videos", "Add clips to the main video track", onPickVideos),
        MediaSourceOption(Icons.Rounded.Audiotrack, "Audio", "Music, voice-over, sound effects", onPickAudio),
        MediaSourceOption(Icons.Rounded.Layers, "Overlay video", "Second video layer for picture-in-picture", onPickOverlay)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CutColors.BackgroundElevated,
        title = { Text("Add media", color = CutColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (icon, title, subtitle, action) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CutColors.Surface)
                            .clickable(onClick = action)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = CutColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = CutColors.TextPrimary)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CutColors.TextSecondary)
                        }
                    }
                }
                Text(
                    "Uses the Android document picker, so no storage permission is required.",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = CutColors.TextSecondary) } }
    )
}

@Composable
private fun ClipContextDialog(
    clip: Clip,
    onDismiss: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSpeed: () -> Unit,
    onFilters: () -> Unit,
    onEffects: () -> Unit,
    onAnimation: () -> Unit,
    onTransition: () -> Unit,
    onCrop: () -> Unit,
    onAudio: () -> Unit,
    onChroma: () -> Unit,
    onPip: () -> Unit,
    onText: () -> Unit,
    onFreeze: () -> Unit,
    onExtractAudio: () -> Unit,
    onAddTrack: (TrackType) -> Unit,
    onCopyStyle: () -> Unit,
    onPasteStyle: () -> Unit,
    onApplyLookToAll: () -> Unit,
    onAutoEnhance: () -> Unit,
    onSendToOverlay: () -> Unit
) {
    val actions = listOf(
        Triple(Icons.Rounded.ContentCut, "Split at playhead", onSplit),
        Triple(Icons.Rounded.ContentCopy, "Duplicate", onDuplicate),
        Triple(Icons.Rounded.Delete, "Delete", onDelete),
        Triple(Icons.Rounded.Speed, "Speed", onSpeed),
        Triple(Icons.Rounded.FilterVintage, "Filters", onFilters),
        Triple(Icons.Rounded.AutoFixHigh, "Effects", onEffects),
        Triple(Icons.Rounded.Animation, "Animation", onAnimation),
        Triple(Icons.Rounded.BlurOn, "Transition", onTransition),
        Triple(Icons.Rounded.Crop, "Crop & rotate", onCrop),
        Triple(Icons.Rounded.Audiotrack, "Audio", onAudio),
        Triple(Icons.Rounded.Tune, "Chroma key", onChroma),
        Triple(Icons.Rounded.PictureInPictureAlt, "Picture in picture", onPip),
        Triple(Icons.Rounded.TextFields, "Text", onText),
        Triple(Icons.Rounded.MoreHoriz, "Freeze frame", onFreeze),
        Triple(Icons.Rounded.MusicNote, "Extract audio", onExtractAudio),
        Triple(Icons.Rounded.ContentCopy, "Copy style", onCopyStyle),
        Triple(Icons.Rounded.ContentPaste, "Paste style", onPasteStyle),
        Triple(Icons.Rounded.DoneAll, "Apply look to all clips", onApplyLookToAll),
        Triple(Icons.Rounded.AutoFixNormal, "Auto enhance", onAutoEnhance),
        Triple(Icons.Rounded.Layers, "Send to overlay", onSendToOverlay)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CutColors.BackgroundElevated,
        title = { Text(clip.name.take(30), color = CutColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                actions.forEach { (icon, label, action) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = action)
                            .padding(vertical = 9.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = CutColors.TextSecondary, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = CutColors.TextPrimary)
                    }
                }
                Text(
                    "TRACKS",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                listOf(TrackType.VIDEO, TrackType.OVERLAY, TrackType.TEXT, TrackType.AUDIO).forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onAddTrack(type) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = CutColors.Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Add ${type.name.lowercase()} track",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CutColors.TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = CutColors.TextSecondary) } }
    )
}
