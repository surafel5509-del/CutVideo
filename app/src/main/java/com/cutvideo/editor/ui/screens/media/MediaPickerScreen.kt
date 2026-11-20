package com.cutvideo.editor.ui.screens.media

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.ui.components.EmptyState
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.components.StatusBadge
import com.cutvideo.editor.ui.theme.CutColors

/**
 * Device media browser. Shows real thumbnails of on-device videos and audio, supports multi-select and
 * imports the selection into the open project. "Browse files" falls back to the SAF document picker.
 */
@Composable
fun MediaPickerScreen(
    vm: MediaPickerViewModel,
    hasOpenProject: Boolean,
    onBack: () -> Unit,
    onImported: (Int, Boolean) -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videos by vm.videos.collectAsStateWithLifecycle()
    val audio by vm.audio.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var showAudio by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<android.net.Uri>()) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        if (result) vm.load()
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        audioGranted = result
        if (result) vm.load()
    }

    LaunchedEffect(Unit) {
        if (granted) vm.load() else permissionLauncher.launch(permission)
    }
    LaunchedEffect(showAudio) {
        if (showAudio && !audioGranted) audioPermissionLauncher.launch(audioPermission)
    }

    val currentGranted = if (showAudio) audioGranted else granted

    val list = if (showAudio) audio else videos

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CutColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(CutColors.Surface)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = CutColors.TextPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Media library", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
                Text(
                    "${list.size} ${if (showAudio) "audio files" else "videos"} • ${selected.size} selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(CutColors.Surface)
                    .clickable { vm.load() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Refresh, "Refresh", tint = CutColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TabChip("Videos", !showAudio) { showAudio = false; selected = emptySet() }
            TabChip("Audio", showAudio) { showAudio = true; selected = emptySet() }
            Spacer(Modifier.weight(1f))
            TabChip("Browse files", false, onClick = onBrowseFiles)
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CutColors.Accent)
                }

                !currentGranted -> EmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Library permission needed",
                    message = "Allow access to read the clips already on this device, or pick individual files with the document picker.",
                    modifier = Modifier.padding(20.dp),
                    action = {
                        GhostButton(
                            text = "Allow access",
                            onClick = {
                                if (showAudio) audioPermissionLauncher.launch(audioPermission)
                                else permissionLauncher.launch(permission)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        GhostButton(text = "Browse files", onClick = onBrowseFiles)
                    }
                )

                list.isEmpty() -> EmptyState(
                    icon = if (showAudio) Icons.Rounded.Audiotrack else Icons.Rounded.Movie,
                    title = if (showAudio) "No audio found" else "No videos found",
                    message = error ?: "Record something first, or import a file from another app.",
                    modifier = Modifier.padding(20.dp),
                    action = { GhostButton(text = "Browse files", onClick = onBrowseFiles) }
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.uri.toString() }) { item ->
                        MediaTile(
                            item = item,
                            selected = item.uri in selected,
                            onClick = {
                                selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                            }
                        )
                    }
                }
            }
        }
    }

    if (selected.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CutColors.BackgroundElevated)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${selected.size} selected",
                style = MaterialTheme.typography.titleMedium,
                color = CutColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (!hasOpenProject) {
                Text(
                    "Open a project to add clips",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.Warning,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
            GradientButton(
                text = "Add to project",
                enabled = hasOpenProject,
                height = 42.dp,
                onClick = {
                    val uris = selected.toList()
                    vm.importSelection(uris, !showAudio) { count ->
                        selected = emptySet()
                        onImported(count, !showAudio)
                    }
                }
            )
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.White else CutColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) CutColors.Accent else CutColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun MediaTile(item: DeviceMedia, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(CutColors.Surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) CutColors.Accent else CutColors.Border,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
    ) {
        if (item.isVideo) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Audiotrack,
                    null,
                    tint = CutColors.AudioTrack,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name.take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (item.durationUs > 0) {
                    Text(
                        TimeText.clock(item.durationUs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
        if (item.width > 0 && item.isVideo) {
            Box(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                StatusBadge("${item.height}p", CutColors.Info)
            }
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    "Selected",
                    tint = CutColors.Accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
