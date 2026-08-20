package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * The Aromex blue gradient header used across the Entities screens (#37).
 *
 * Draws **edge-to-edge under the status bar** with **rounded bottom corners**
 * (the design's floating-header look) — the content receives the status-bar
 * inset as its top padding so it clears the notification row. Callers must have
 * edge-to-edge enabled on the Activity (MainActivity does).
 *
 * Content is supplied by the caller (each entities screen has a different
 * header layout) and laid out in a [Column] with horizontal screen padding
 * already applied.
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
    bottomRadius: Dp = 28.dp,
    horizontalPadding: Dp = AromexTheme.dimensions.screenPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = bottomRadius, bottomEnd = bottomRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.headerGradientStart, colors.headerGradientEnd),
                ),
            )
            .padding(top = statusBarPadding + dimensions.space16)
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = dimensions.space20,
            ),
        content = content,
    )
}
