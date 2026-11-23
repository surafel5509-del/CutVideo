package com.cutvideo.editor.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.cutvideo.editor.data.db.ExportEntity
import com.cutvideo.editor.data.repository.ProjectSummary
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.EffectPreset
import com.cutvideo.editor.domain.model.FilterPreset
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.domain.model.TransitionType
import com.cutvideo.editor.editor.AnimationEngine
import com.cutvideo.editor.editor.TextRasterizer
import com.cutvideo.editor.ui.theme.CutColors
import com.cutvideo.editor.ui.theme.FontFamilies
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectCard(
    project: ProjectSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .width(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CutColors.BackgroundElevated)
            .border(1.dp, CutColors.Border, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.32f)
                .clip(RoundedCornerShape(14.dp))
                .background(CutColors.BackgroundSunken)
        ) {
            val path = project.thumbnailPath
            if (path != null && File(path).exists()) {
                AsyncImage(
                    model = File(path),
                    contentDescription = project.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(CutColors.Surface, CutColors.SurfaceHigh))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = CutColors.TextTertiary)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(project.durationLabel, style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
            if (project.isDraft) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CutColors.Warning.copy(alpha = 0.9f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("DRAFT", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            project.name,
            style = MaterialTheme.typography.titleMedium,
            color = CutColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(project.dateLabel, style = MaterialTheme.typography.bodySmall, color = CutColors.TextSecondary)
            Text("${project.clipCount} clips", style = MaterialTheme.typography.bodySmall, color = CutColors.TextTertiary)
        }
        Text(project.aspect.label, style = MaterialTheme.typography.labelSmall, color = CutColors.Accent)
    }
}

@Composable
fun TemplateCard(
    name: String,
    description: String,
    aspect: AspectRatio,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(196.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), CutColors.BackgroundElevated)))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                aspect.label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Filter card. The thumbnail is the real frame the user is working on, pushed through the C++/GPU
 * colour pipeline (see FilterPreviewRenderer). No pre-baked marketing images.
 */
@Composable
fun FilterCard(
    preset: FilterPreset,
    preview: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableSwatch(
        selected = selected,
        onClick = onClick,
        accent = preset.accent,
        modifier = modifier.width(88.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp))
                    .background(CutColors.BackgroundSunken)
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = preset.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ShimmerBox(modifier = Modifier.fillMaxSize(), corner = 0.dp)
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(preset.accent)
                    )
                }
            }
            Text(
                preset.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White else CutColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun EffectCard(
    preset: EffectPreset,
    preview: Bitmap?,
    applied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableSwatch(
        selected = applied,
        onClick = onClick,
        accent = preset.accent,
        modifier = modifier.width(96.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp))
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = preset.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ShimmerBox(modifier = Modifier.fillMaxSize(), corner = 0.dp)
                }
                if (preset.premium) {
                    StatusBadge("PRO", preset.accent, Modifier.align(Alignment.TopStart).padding(4.dp))
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (applied) Color.White else CutColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    preset.group,
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

/** Transition / animation card with a looping preview of the real curve. */
@Composable
fun MotionCard(
    title: String,
    subtitle: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    preview: MotionPreview,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "motion")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "motionProgress"
    )

    SelectableSwatch(
        selected = selected,
        onClick = onClick,
        accent = accent,
        modifier = modifier.width(104.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp))
                    .background(CutColors.BackgroundSunken),
                contentAlignment = Alignment.Center
            ) {
                val frame = when (preview) {
                    is MotionPreview.Animation -> AnimationEngine.sample(preview.type, progress)
                    is MotionPreview.Transition -> AnimationEngine.sampleTransition(preview.type, progress)
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(frame.scale.coerceIn(0.2f, 2f))
                        .padding(2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = frame.alpha), CutColors.AccentSecondary.copy(alpha = frame.alpha))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = frame.alpha * 0.5f), RoundedCornerShape(10.dp))
                )
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.White else CutColors.TextSecondary,
                    maxLines = 1
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

sealed interface MotionPreview {
    data class Animation(val type: ClipAnimationType) : MotionPreview
    data class Transition(val type: TransitionType) : MotionPreview
}

/** Timeline clip thumbnail (real decoded frame). */
@Composable
fun ClipFrame(uri: String, atUs: Long, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .videoFrameMillis(atUs / 1000L)
            .crossfade(120)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

/** Small live text thumbnail used by the text panel and text editor. */
@Composable
fun TextPreviewThumb(spec: TextSpec, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CutColors.Accent.copy(alpha = 0.2f) else CutColors.Surface)
            .border(1.dp, if (selected) CutColors.Accent else CutColors.Border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = spec.text.ifBlank { "Text" },
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamilies.composeFamily(spec.fontId),
                fontWeight = if (spec.bold) FontWeight.Bold else FontWeight.Medium
            ),
            color = Color(spec.color),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ExportCard(
    export: ExportEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CutColors.BackgroundElevated)
            .border(1.dp, CutColors.Border, RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CutColors.BackgroundSunken)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                val thumb = export.thumbnailPath
                if (thumb != null && File(thumb).exists()) {
                    AsyncImage(model = File(thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    export.projectName,
                    style = MaterialTheme.typography.titleMedium,
                    color = CutColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${export.width}×${export.height} • ${export.codec} • ${export.frameRate}fps",
                    style = MaterialTheme.typography.bodySmall,
                    color = CutColors.TextSecondary
                )
                Text(
                    com.cutvideo.editor.core.TimeText.bytes(export.sizeBytes) + " • " +
                        com.cutvideo.editor.core.TimeText.relativeDate(export.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = CutColors.TextTertiary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Play", onClick = onPlay, icon = Icons.Rounded.PlayArrow)
            GhostButton("Share", onClick = onShare, icon = Icons.Rounded.Movie)
            GhostButton("Delete", onClick = onDelete)
        }
    }
}

/** Text clip preview drawn over the preview surface. */
@Composable
fun TextOverlayPreview(clip: Clip, progress: Float, modifier: Modifier = Modifier) {
    val spec = clip.text ?: return
    val frame = AnimationEngine.composeLocal(clip, (clip.durationUs * progress).toLong())
    Box(
        modifier = modifier
            .scale(frame.scale.coerceIn(0.2f, 2f))
            .padding(2.dp)
    ) {
        Text(
            text = spec.text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamilies.composeFamily(spec.fontId),
                fontWeight = if (spec.bold) FontWeight.Bold else FontWeight.Normal
            ),
            color = Color(spec.color).copy(alpha = frame.alpha)
        )
    }
}

/** Renders a real text spec to a bitmap (used by the text editor preview and export pre-flight). */
fun rasterizeText(spec: TextSpec, width: Int, height: Int): Bitmap? =
    TextRasterizer.rasterize(spec, width, height)?.bitmap
