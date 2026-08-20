package com.humblesolutions.aromex.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reusable brand header: a blue vertical gradient with a faint outlined
 * circle in the top-right. **Rectangular — no rounded corners** (per the
 * updated design after ticket #21 review).
 *
 * Draws **edge-to-edge under the system status bar** — content uses the
 * status-bar inset internally as its top padding so the mark + wordmark sit
 * below the status-bar icons. Callers must have edge-to-edge enabled on the
 * Activity (`WindowCompat.setDecorFitsSystemWindows(false)`); MainActivity
 * does so.
 *
 * The Box's total height is FIXED to `statusBarInset + contentHeight` so the
 * decorative Canvas (which uses `matchParentSize`) does not inflate the Box
 * to fill the whole screen.
 */
@Composable
fun AromexGradientHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    contentHeight: Dp = 220.dp,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val totalHeight = statusBarPadding + contentHeight

    val ambient = rememberInfiniteTransition(label = "circleAmbient")
    val phase by ambient.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
        ),
        label = "phase",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.headerGradientStart, colors.headerGradientEnd),
                ),
            ),
    ) {
        // matchParentSize matches the Box's already-known height without
        // participating in constraints — the critical difference from
        // fillMaxSize which would push the Box to fill the whole parent Column.
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
            modifier = Modifier
                .fillMaxSize()
                // Extra top gap below the status-bar icons so the mark sits
                // comfortably below the notification row, per PM feedback.
                .padding(top = statusBarPadding + dimensions.space16)
                .padding(
                    horizontal = dimensions.screenPadding,
                    vertical = dimensions.space24,
                ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AromexMark(size = 44.dp, foreground = colors.onBrand)
                Spacer(Modifier.width(dimensions.space12))
                Text(
                    text = "AROMEX",
                    color = colors.onBrand,
                    style = AromexTheme.typography.display.copy(
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column {
                Text(
                    text = title,
                    color = colors.onBrand,
                    style = AromexTheme.typography.screenTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(dimensions.space8))
                Text(
                    text = subtitle,
                    color = colors.onBrand.copy(alpha = 0.75f),
                    style = AromexTheme.typography.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
