package com.humblesolutions.aromex.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.components.AromexMark
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Splash — ticket-#21 restyle. Same blue gradient, same faint outlined-circle
 * decoration, and same `AromexMark` as the Login gradient header — so Splash
 * visually **flows** into Login (the user sees the same mark and brand
 * treatment on both).
 */
@Composable
fun SplashScreen() {
    val colors = AromexTheme.colors
    val opacity = remember { Animatable(0f) }
    val iconScale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        launch { opacity.animateTo(1f, animationSpec = tween(durationMillis = 800)) }
        iconScale.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    val ambient = rememberInfiniteTransition(label = "circleAmbient")
    val phase by ambient.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
        ),
        label = "phase",
    )

    val background = Brush.verticalGradient(
        colors = listOf(colors.headerGradientStart, colors.headerGradientEnd),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative outlined circles in the top-right — same as
        // AromexGradientHeader on Login. matchParentSize keeps the Box's
        // known size (fillMaxSize on the parent Box) without pushing it.
        Canvas(modifier = Modifier.matchParentSize()) {
            val d = size.width * 0.7f
            val p = phase.toDouble()
            val bigCos = cos(p).toFloat()
            val bigSin = sin(p).toFloat()
            val bigBreath = 1f + 0.02f * sin(p * 0.35).toFloat()
            val smCos = cos(p * 0.9 + PI).toFloat()
            val smSin = sin(p * 0.9 + PI).toFloat()
            val smBreath = 1f + 0.025f * sin(p * 0.5 + PI / 2).toFloat()

            drawCircle(
                color = colors.headerDecoration,
                radius = d / 2f * bigBreath,
                center = Offset(
                    x = size.width * 0.9f + bigCos * 6.dp.toPx(),
                    y = d * 0.35f + bigSin * 5.dp.toPx(),
                ),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = colors.headerDecoration.copy(alpha = 0.6f),
                radius = d * 0.16f * smBreath,
                center = Offset(
                    x = size.width * 0.85f + smCos * 8.dp.toPx(),
                    y = d * 0.21f + smSin * 6.dp.toPx(),
                ),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .scale(iconScale.value)
                    .alpha(opacity.value),
            ) {
                AromexMark(size = 88.dp, foreground = colors.onBrand, strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(40.dp))
            Text(
                text = strings(Strings.splash_app_name),
                color = colors.onBrand,
                fontSize = 48.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.25f),
                        offset = Offset(0f, 4f),
                        blurRadius = 10f,
                    ),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(opacity.value),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = strings(Strings.splash_tagline),
                color = colors.onBrand.copy(alpha = 0.9f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(opacity.value),
            )
        }
    }
}
