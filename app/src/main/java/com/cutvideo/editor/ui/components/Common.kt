package com.cutvideo.editor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cutvideo.editor.ui.theme.CutColors

/**
 * The CutVideo mark: a violet disc cut by a blade with a play triangle in the gap.
 * Drawn with vectors so it scales crisply anywhere.
 */
@Composable
fun CutVideoLogo(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    animate: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "logo")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "logoPulse"
    )
    val scale = if (animate) pulse else 1f

    Canvas(modifier = modifier.size(size).scale(scale)) {
        val r = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CutColors.Accent, CutColors.AccentSecondary),
                center = centre,
                radius = r
            ),
            radius = r,
            center = centre
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.12f),
            radius = r * 0.74f,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.06f)
        )

        // Play triangle
        val path = Path().apply {
            moveTo(centre.x - r * 0.22f, centre.y - r * 0.36f)
            lineTo(centre.x + r * 0.40f, centre.y)
            lineTo(centre.x - r * 0.22f, centre.y + r * 0.36f)
            close()
        }
        drawPath(path, Color.White)

        // Blade cut
        drawRect(
            color = CutColors.Background,
            topLeft = Offset(0f, centre.y + r * 0.10f),
            size = Size(this.size.width, r * 0.20f)
        )
        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(0f, centre.y + r * 0.10f),
            size = Size(r * 0.34f, r * 0.20f)
        )
    }
}

/** Wordmark used on the splash and home headers. */
@Composable
fun CutVideoWordmark(modifier: Modifier = Modifier, logoSize: Dp = 44.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CutVideoLogo(size = logoSize)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "CutVideo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = CutColors.TextPrimary
            )
            Text(
                text = "STUDIO",
                style = MaterialTheme.typography.labelSmall,
                color = CutColors.Accent,
                letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2
            )
        }
    }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp)
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "buttonScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (enabled) CutColors.accentGradientHorizontal else Brush.horizontalGradient(listOf(CutColors.SurfaceHigh, CutColors.SurfaceHigh)))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.White else CutColors.TextTertiary
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CutColors.Surface.copy(alpha = 0.7f))
            .border(1.dp, CutColors.Border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = CutColors.TextPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = CutColors.TextPrimary)
    }
}

/** Rounded translucent container used for every panel in the editor. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    borderColor: Color = CutColors.Border,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(CutColors.panelGradient)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = CutColors.Accent
) {
    val background by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.20f) else CutColors.Surface.copy(alpha = 0.6f),
        animationSpec = tween(180),
        label = "chipBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) accent else CutColors.Border,
        animationSpec = tween(180),
        label = "chipBorder"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) accent else CutColors.TextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else CutColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = CutColors.TextPrimary
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = CutColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(CutColors.AccentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = CutColors.Accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = CutColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = CutColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = action)
        }
    }
}

/** Shimmer placeholder used while real thumbnails decode. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "shimmerProgress"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    colors = listOf(CutColors.Surface, CutColors.SurfaceHigh, CutColors.Surface),
                    start = Offset(progress * 600f, 0f),
                    end = Offset(progress * 600f + 300f, 300f)
                )
            )
    )
}

/** Small status chip (e.g. "Experimental", "Export only"). */
@Composable
fun StatusBadge(text: String, color: Color = CutColors.Warning, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Draws the little coloured bar under a section title. */
fun DrawScope.accentUnderline(width: Float = 28f, color: Color = CutColors.Accent) {
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, 0f),
        size = Size(width, 3f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f)
    )
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = CutColors.TextPrimary) {
    Column(modifier = modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = CutColors.TextTertiary)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
