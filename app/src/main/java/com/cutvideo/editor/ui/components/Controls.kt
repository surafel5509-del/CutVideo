package com.cutvideo.editor.ui.components

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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cutvideo.editor.ui.theme.CutColors
import kotlin.math.roundToInt

/**
 * Slider row with a label, live value and reset. Used by every adjustment in the editor.
 *
 * [onValueChange] is fired continuously (live preview) and [onValueChangeFinished] records a single
 * undo checkpoint for the gesture.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    defaultValue: Float = 0f,
    format: (Float) -> String = { it.toString() },
    accent: Color = CutColors.Accent
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = CutColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (value != defaultValue) accent else CutColors.TextTertiary
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { onValueChange(defaultValue) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Reset $label",
                        tint = CutColors.TextTertiary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = CutColors.SurfaceHigh
            )
        )
    }
}

/** Big selectable swatch used by the filter and effect cards. */
@Composable
fun SelectableSwatch(
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else CutColors.Surface.copy(alpha = 0.55f))
            .border(
                width = if (selected) 1.6.dp else 1.dp,
                color = if (selected) accent else CutColors.Border,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
    ) {
        content()
    }
}

/** Compact numeric stepper used by the speed panel and export settings. */
@Composable
fun OptionRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) CutColors.Accent.copy(alpha = 0.22f) else CutColors.Surface.copy(alpha = 0.6f))
                    .border(
                        1.dp,
                        if (selected) CutColors.Accent else CutColors.Border,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else CutColors.TextSecondary
                )
            }
        }
    }
}

/** Horizontal colour palette used by text and chroma-key pickers. */
@Composable
fun ColorPalette(
    colors: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { color ->
            val isSelected = (color.value == selected.value)
            Box(
                modifier = Modifier
                    .size(if (isSelected) 34.dp else 28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Color.White else CutColors.BorderStrong,
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) }
            )
        }
    }
}

/** Toggle row with a switch-like pill. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = CutColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CutColors.TextSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(CircleShape)
                .background(if (checked) CutColors.Accent else CutColors.SurfaceHigh)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/** Two-way segmented control with a sliding selection background. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(CutColors.Surface.copy(alpha = 0.7f))
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) CutColors.Accent else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.White else CutColors.TextSecondary
                )
            }
        }
    }
}

/** Value formatted as a percentage for the adjustment panels. */
fun percentFormat(value: Float): String = "${(value * 100).roundToInt()}"

/** Remembers the initial value so a gesture can be reset reliably. */
@Composable
fun rememberGestureStart(value: Float): MutableStateHolder {
    val holder = remember { MutableStateHolder() }
    holder.current = value
    return holder
}

class MutableStateHolder {
    var current: Float by mutableStateOf(0f)
}
