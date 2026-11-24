package com.cutvideo.editor.ui.screens.effects

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cutvideo.editor.domain.model.AnimationCatalog
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.EffectsCatalog
import com.cutvideo.editor.domain.model.FilterCatalog
import com.cutvideo.editor.domain.model.TransitionCatalog
import com.cutvideo.editor.ui.components.EffectCard
import com.cutvideo.editor.ui.components.FilterCard
import com.cutvideo.editor.ui.components.MotionCard
import com.cutvideo.editor.ui.components.MotionPreview
import com.cutvideo.editor.ui.components.SegmentedControl
import com.cutvideo.editor.ui.components.rememberLookPreview
import com.cutvideo.editor.ui.screens.editor.SessionViewModel
import com.cutvideo.editor.ui.theme.CutColors

/**
 * Full-screen effects library. Every card renders the real colour pipeline — the still comes from the
 * selected clip and is pushed through the same native look/kernel used by the preview and export.
 */
@Composable
fun EffectsLibraryScreen(
    vm: SessionViewModel,
    projectId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val selectedId by vm.selectedClipId.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var groupIndex by remember { mutableStateOf(0) }
    var source by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(projectId) { vm.open(projectId) }

    val document = doc
    val clip: Clip? = document?.clip(selectedId ?: "")
        ?: document?.videoClips()?.firstOrNull()
        ?: document?.overlayClips()?.firstOrNull()

    LaunchedEffect(clip?.id) {
        val target = clip ?: return@LaunchedEffect
        if (target.uri.isBlank()) return@LaunchedEffect
        source = vm.thumbnails.frame(target.uri, target.sourceInUs, 320)
    }

    val groups = when (tab) {
        0 -> FilterCatalog.groups()
        1 -> EffectsCatalog.groups()
        else -> emptyList()
    }
    val activeGroup = groups.getOrNull(groupIndex)

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
                Text("Effects library", style = MaterialTheme.typography.headlineSmall, color = CutColors.TextPrimary)
                Text(
                    clip?.name?.take(26) ?: "Select a clip first",
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
        }

        SegmentedControl(
            options = listOf("Filters", "Effects", "Transitions", "Motion"),
            selectedIndex = tab,
            onSelect = { tab = it; groupIndex = 0 },
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        if (groups.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(groups) { group ->
                    val selected = group == activeGroup
                    Text(
                        group,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Color.White else CutColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CutColors.Accent else CutColors.Surface)
                            .clickable { groupIndex = groups.indexOf(group) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when (tab) {
            0 -> LazyVerticalGrid(
                columns = GridCells.Adaptive(92.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val presets = FilterCatalog.presets.filter { activeGroup == null || it.group == activeGroup }
                items(presets, key = { it.id }) { preset ->
                    val preview = clip?.let {
                        rememberLookPreview(
                            source = source,
                            clip = it,
                            aspect = document?.aspect ?: com.cutvideo.editor.domain.model.AspectRatio.RATIO_16_9,
                            nativeEngine = vm.nativeEngine,
                            filter = preset
                        )
                    }
                    FilterCard(
                        preset = preset,
                        preview = preview,
                        selected = clip?.filter?.id == preset.id,
                        onClick = {
                            if (clip == null) {
                                vm.notify("Select a clip on the timeline first")
                            } else {
                                vm.setFilter(clip.id, preset.id)
                                vm.notify("${preset.name} applied")
                            }
                        }
                    )
                }
            }

            1 -> LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val presets = EffectsCatalog.all().filter { activeGroup == null || it.group == activeGroup }
                items(presets, key = { it.id }) { preset ->
                    // Effects that render only in the GPU chain have no CPU thumbnail pipeline.
                    val pro = preset.id in setOf(
                        "gaussian_blur", "glow", "bloom_pro", "drop_shadow", "vignette_pro", "grain_pro"
                    )
                    val preview = if (pro) null else clip?.let {
                        rememberLookPreview(
                            source = source,
                            clip = it,
                            aspect = document?.aspect ?: com.cutvideo.editor.domain.model.AspectRatio.RATIO_16_9,
                            nativeEngine = vm.nativeEngine,
                            effect = preset
                        )
                    }
                    EffectCard(
                        preset = preset,
                        preview = preview,
                        previewAvailable = !pro,
                        applied = clip?.effects?.any { it.id == preset.id } == true,
                        onClick = {
                            if (clip == null) {
                                vm.notify("Select a clip on the timeline first")
                            } else {
                                vm.toggleEffect(clip.id, preset.id, preset.defaultIntensity)
                                vm.notify("${preset.name} toggled")
                            }
                        }
                    )
                }
            }

            2 -> MotionGrid(
                titleFor = { it.name },
                subtitleFor = { it.description },
                accentFor = { it.accent },
                presets = TransitionCatalog.presets,
                selectedCheck = { preset -> clip?.transitionOut?.type == preset.type },
                preview = { MotionPreview.Transition(it.type) },
                onPick = { preset ->
                    val current = clip
                    val next = current?.let { c ->
                        document?.videoClips()?.sortedBy { it.startUs }?.let { list ->
                            list.getOrNull(list.indexOfFirst { it.id == c.id } + 1)
                        }
                    }
                    if (current == null || next == null) {
                        vm.notify("Add a clip after this one to place a transition")
                    } else {
                        vm.editor.setTransition(current.id, next.id, com.cutvideo.editor.domain.model.TransitionSpec(preset.type))
                        vm.notify("${preset.name} transition added")
                    }
                }
            )

            else -> MotionGrid(
                titleFor = { it.name },
                subtitleFor = { "" },
                accentFor = { it.accent },
                presets = AnimationCatalog.presets,
                selectedCheck = { preset -> clip?.animationIn?.type == preset.type },
                preview = { MotionPreview.Animation(it.type) },
                onPick = { preset ->
                    val current = clip
                    if (current == null) {
                        vm.notify("Select a clip first")
                    } else {
                        vm.setAnimation(current.id, preset.type, current.animationOut.type, 600_000L)
                        vm.notify("${preset.name} applied to the clip")
                    }
                }
            )
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
private fun <T> MotionGrid(
    titleFor: (T) -> String,
    subtitleFor: (T) -> String,
    accentFor: (T) -> Color,
    presets: List<T>,
    selectedCheck: (T) -> Boolean,
    preview: (T) -> MotionPreview,
    onPick: (T) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(presets) { preset ->
            MotionCard(
                title = titleFor(preset),
                subtitle = subtitleFor(preset),
                accent = accentFor(preset),
                selected = selectedCheck(preset),
                preview = preview(preset),
                onClick = { onPick(preset) },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CutColors.Border, RoundedCornerShape(18.dp))
            )
        }
    }
}
