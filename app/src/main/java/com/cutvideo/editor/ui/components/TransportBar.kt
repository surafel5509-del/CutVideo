package com.cutvideo.editor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cutvideo.editor.core.TimeText
import com.cutvideo.editor.ui.theme.CutColors

/** Transport controls: undo/redo, frame stepping, play/pause, timecode, mute, fullscreen, speed. */
@Composable
fun TransportBar(
    isPlaying: Boolean,
    positionUs: Long,
    durationUs: Long,
    frameRate: Int,
    speed: Float,
    muted: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleMute: () -> Unit,
    onSpeedClick: () -> Unit,
    onFullscreen: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CutColors.BackgroundElevated)
            .border(1.dp, CutColors.Border, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportIcon(Icons.Rounded.Undo, "Undo", enabled = canUndo, onClick = onUndo)
        TransportIcon(Icons.Rounded.Redo, "Redo", enabled = canRedo, onClick = onRedo)

        Spacer(Modifier.weight(1f))

        TransportIcon(Icons.Rounded.SkipPrevious, "Previous frame", onClick = onStepBack)

        val scale by animateFloatAsState(
            targetValue = if (isPlaying) 1.06f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
            label = "playScale"
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(CutColors.accentGradientHorizontal)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        TransportIcon(Icons.Rounded.SkipNext, "Next frame", onClick = onStepForward)

        Spacer(Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${TimeText.clockWithFrames(positionUs, frameRate)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CutColors.TextPrimary
            )
            Text(
                "/ ${TimeText.clock(durationUs)}",
                style = MaterialTheme.typography.labelSmall,
                color = CutColors.TextTertiary
            )
        }

        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(CutColors.Surface.copy(alpha = 0.8f))
                    .clickable(onClick = onSpeedClick)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Speed, null, tint = CutColors.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "${speed}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = CutColors.TextPrimary
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            TransportIcon(
                if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                if (muted) "Unmute" else "Mute",
                onClick = onToggleMute,
                tint = if (muted) CutColors.Danger else CutColors.TextSecondary
            )
            TransportIcon(Icons.Rounded.Fullscreen, "Fullscreen preview", onClick = onFullscreen)
        }
    }
}

@Composable
private fun TransportIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    tint: Color = CutColors.TextSecondary
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else CutColors.TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Small badge row used above the preview (project aspect / resolution / muted). */
@Composable
fun PreviewBadgeRow(
    aspectLabel: String,
    resolutionLabel: String,
    muted: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BadgePill(aspectLabel)
        BadgePill(resolutionLabel)
        if (muted) BadgePill("Muted", CutColors.Danger)
    }
}

@Composable
private fun BadgePill(text: String, color: Color = CutColors.TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
