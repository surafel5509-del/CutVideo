package com.cutvideo.editor.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutvideo.editor.ui.components.CutVideoLogo
import com.cutvideo.editor.ui.theme.CutColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Brand splash: logo scales in, wordmark fades, then the home screen takes over. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 320f))
        }
        launch {
            alpha.animateTo(1f, tween(520, easing = LinearOutSlowInEasing))
        }
        delay(360)
        taglineAlpha.animateTo(1f, tween(420))
        delay(760)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(CutColors.Accent.copy(alpha = 0.22f), CutColors.Background),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CutVideoLogo(size = 116.dp, animate = false, modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            })
            Spacer(Modifier.height(22.dp))
            Text(
                "CutVideo",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = CutColors.TextPrimary,
                modifier = Modifier.alpha(alpha.value)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "PRO VIDEO EDITING",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.2.sp),
                color = CutColors.Accent,
                modifier = Modifier.alpha(taglineAlpha.value)
            )
        }
    }
}
