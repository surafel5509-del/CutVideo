package com.cutvideo.editor.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.data.repository.ProjectSummary
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.ui.components.CutVideoWordmark
import com.cutvideo.editor.ui.components.EmptyState
import com.cutvideo.editor.ui.components.GhostButton
import com.cutvideo.editor.ui.components.GradientButton
import com.cutvideo.editor.ui.components.ProjectCard
import com.cutvideo.editor.ui.components.SectionHeader
import com.cutvideo.editor.ui.components.TemplateCard
import com.cutvideo.editor.ui.theme.CutColors

/**
 * Home: brand header, new project, recents, drafts, templates and the entry points to the media
 * library, exported videos and settings.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenProject: (String) -> Unit,
    onProjectCreated: (String) -> Unit,
    onOpenMediaLibrary: () -> Unit,
    onOpenExported: () -> Unit,
    onOpenSettings: () -> Unit,
    incomingMedia: android.net.Uri?,
    onIncomingMediaHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var newProjectDialog by remember { mutableStateOf(false) }
    var actionProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var renameProject by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(incomingMedia) {
        if (incomingMedia != null) {
            vm.createProject("Shared video", settings.defaultAspect, 30, null, onProjectCreated)
            onIncomingMediaHandled()
        }
    }

    val accentList = listOf(CutColors.Accent, CutColors.Info, CutColors.Success, CutColors.Warning)

    Box(
        Modifier
            .fillMaxSize()
            .background(CutColors.Background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(CutColors.glowGradient)
        )
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CutVideoWordmark()
                Spacer(Modifier.weight(1f))
                CircleIconButton(Icons.Rounded.VideoLibrary, "Exported videos", onOpenExported)
                Spacer(Modifier.width(8.dp))
                CircleIconButton(Icons.Rounded.Settings, "Settings", onOpenSettings)
            }

            // Hero card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(CutColors.AccentSecondary.copy(alpha = 0.95f), CutColors.Accent.copy(alpha = 0.75f))
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        "Start a new edit",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        "Import your clips, cut on a real multi-track timeline and export in up to 4K.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(
                            text = "New project",
                            onClick = { newProjectDialog = true },
                            icon = Icons.Rounded.Add,
                            height = 46.dp,
                            contentPadding = PaddingValues(horizontal = 18.dp)
                        )
                        GhostButton(
                            text = "Media library",
                            onClick = onOpenMediaLibrary,
                            icon = Icons.Rounded.Collections
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (drafts.isNotEmpty()) {
                SectionHeader(
                    title = "Drafts & recovery",
                    action = "${drafts.size}",
                    onAction = null,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(drafts, key = { it.id }) { draft ->
                        ProjectCard(
                            project = draft,
                            onClick = { onOpenProject(draft.id) },
                            onLongClick = { actionProject = draft }
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            SectionHeader(
                title = "Recent projects",
                action = "${projects.size}",
                onAction = null,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (projects.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Movie,
                    title = "No projects yet",
                    message = "Tap New project, pick a video and start cutting. Everything you edit is saved automatically.",
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onOpenProject(project.id) },
                            onLongClick = { actionProject = project }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            SectionHeader(
                title = "Templates",
                action = "Tap to start",
                onAction = null,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(vm.templates, key = { it.id }) { template ->
                    TemplateCard(
                        name = template.name,
                        description = template.description,
                        aspect = template.aspect,
                        accent = accentList[template.accentIndex % accentList.size],
                        onClick = {
                            vm.createProject(
                                template.name,
                                template.aspect,
                                template.frameRate,
                                template,
                                onProjectCreated
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(CutColors.BackgroundElevated)
                    .border(1.dp, CutColors.Border, RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenMediaLibrary)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CutColors.AccentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Collections, null, tint = CutColors.Accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Media library", style = MaterialTheme.typography.titleMedium, color = CutColors.TextPrimary)
                    Text(
                        "Browse clips and audio already on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = CutColors.TextSecondary
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineMedium, color = CutColors.TextTertiary)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Draft autosave every ${settings.autosaveSeconds}s • ${settings.exportResolution.label} default export",
                style = MaterialTheme.typography.labelSmall,
                color = CutColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(28.dp))
        }

        AnimatedVisibility(
            visible = message != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            Text(
                message ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(CutColors.SurfaceHighest)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }

    if (newProjectDialog) {
        NewProjectDialog(
            defaultAspect = settings.defaultAspect,
            onDismiss = { newProjectDialog = false },
            onCreate = { name, aspect ->
                newProjectDialog = false
                vm.createProject(name, aspect, 30, null, onProjectCreated)
            }
        )
    }

    actionProject?.let { project ->
        AlertDialog(
            onDismissRequest = { actionProject = null },
            containerColor = CutColors.BackgroundElevated,
            title = { Text(project.name, color = CutColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "${project.clipCount} clips • ${project.durationLabel} • ${project.dateLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CutColors.TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Open" to { onOpenProject(project.id); actionProject = null },
                        "Rename" to { renameProject = project; actionProject = null },
                        "Duplicate" to { vm.duplicateProject(project); actionProject = null },
                        "Delete" to { vm.deleteProject(project.id); actionProject = null }
                    ).forEach { (label, action) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = action)
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (label == "Delete") {
                                Icon(Icons.Rounded.Delete, null, tint = CutColors.Danger, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (label == "Delete") CutColors.Danger else CutColors.TextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionProject = null }) { Text("Close") } }
        )
    }

    renameProject?.let { project ->
        var value by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renameProject = null },
            containerColor = CutColors.BackgroundElevated,
            title = { Text("Rename project", color = CutColors.TextPrimary) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProject(project.id, value)
                    renameProject = null
                }) { Text("Save", color = CutColors.Accent) }
            },
            dismissButton = { TextButton(onClick = { renameProject = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(CutColors.Surface.copy(alpha = 0.75f))
            .border(1.dp, CutColors.Border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = CutColors.TextPrimary, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun NewProjectDialog(
    defaultAspect: AspectRatio,
    onDismiss: () -> Unit,
    onCreate: (String, AspectRatio) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(defaultAspect) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CutColors.BackgroundElevated,
        title = { Text("New project", color = CutColors.TextPrimary) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Project name") }
                )
                Spacer(Modifier.height(14.dp))
                Text("Aspect ratio", style = MaterialTheme.typography.labelMedium, color = CutColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                AspectRatio.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { option ->
                            Text(
                                option.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (aspect == option) Color.White else CutColors.TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (aspect == option) CutColors.Accent else CutColors.Surface)
                                    .clickable { aspect = option }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, aspect) }) {
                Text("Create", color = CutColors.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CutColors.TextSecondary) } }
    )
}
