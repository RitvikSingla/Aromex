package com.humblesolutions.aromex.ui.splash

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.components.AromexMark
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlinx.coroutines.launch

@Composable
fun SplashScreen() {
    val colors = AromexTheme.colors
    val opacity = remember { Animatable(0f) }
    val markScale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        launch { opacity.animateTo(1f, animationSpec = tween(durationMillis = 800)) }
        markScale.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    val background = Brush.verticalGradient(
        colors = listOf(colors.headerGradientStart, colors.headerGradientEnd),
    )

    Box(
        modifier = Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative circles top-right, matching the login brand panel.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 1.5.dp.toPx())
            val r = size.width * 0.35f
            drawCircle(
                color = colors.headerDecoration,
                radius = r,
                center = Offset(x = size.width * 0.95f, y = size.height * 0.15f),
                style = stroke,
            )
            drawCircle(
                color = colors.headerDecoration.copy(alpha = 0.6f),
                radius = r * 0.55f,
                center = Offset(x = size.width * 0.85f, y = size.height * 0.20f),
                style = stroke,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            AromexMark(
                size = 96.dp,
                foreground = colors.onBrand,
                modifier = Modifier.scale(markScale.value).alpha(opacity.value),
            )
            Spacer(Modifier.height(40.dp))
            Text(
                text = strings(Strings.splash_app_name),
                color = colors.onBrand,
                style = AromexTheme.typography.display.copy(fontSize = 40.sp, lineHeight = 48.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(opacity.value),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = strings(Strings.splash_tagline),
                color = colors.onBrand.copy(alpha = 0.9f),
                style = AromexTheme.typography.body.copy(fontSize = 14.sp, letterSpacing = 2.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(opacity.value),
            )
        }
    }
}
