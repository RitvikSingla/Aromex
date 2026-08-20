package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * The left brand panel on desktop Login: full-height vertical
 * `#40548A → #283A63` gradient with faint decorative circle outlines
 * (top-right + lower-left), the AROMEX mark + wordmark top-left, an eyebrow /
 * headline / body block in the middle-left, and the "A Humble Solutions
 * Product" pill at the bottom-left.
 */
@Composable
fun AromexBrandPanel(modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.headerGradientStart, colors.headerGradientEnd),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = Stroke(width = 1.5.dp.toPx())
            val bigR = size.width * 0.75f
            drawCircle(
                color = colors.headerDecoration,
                radius = bigR,
                center = Offset(x = size.width * 1.05f, y = size.height * 0.20f),
                style = stroke,
            )
            drawCircle(
                color = colors.headerDecoration.copy(alpha = 0.6f),
                radius = bigR * 0.55f,
                center = Offset(x = size.width * 0.95f, y = size.height * 0.28f),
                style = stroke,
            )
            drawCircle(
                color = colors.headerDecoration.copy(alpha = 0.5f),
                radius = size.width * 0.55f,
                center = Offset(x = -size.width * 0.10f, y = size.height * 0.95f),
                style = stroke,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dims.space32, vertical = dims.space32),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: mark + wordmark
            Row(verticalAlignment = Alignment.CenterVertically) {
                AromexMark(size = 32.dp, foreground = colors.onBrand)
                Spacer(Modifier.width(dims.space12))
                Text(
                    text = strings(Strings.splash_app_name),
                    color = colors.onBrand,
                    style = AromexTheme.typography.display.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Middle: eyebrow / headline / body
            Column(modifier = Modifier.widthIn(max = 360.dp)) {
                Text(
                    text = strings(Strings.login_desktop_eyebrow),
                    color = colors.onBrand.copy(alpha = 0.60f),
                    style = AromexTheme.typography.fieldLabel.copy(letterSpacing = 2.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(dims.space16))
                Text(
                    text = strings(Strings.login_desktop_headline),
                    color = colors.onBrand,
                    style = AromexTheme.typography.screenTitle.copy(
                        fontSize = 40.sp,
                        lineHeight = 48.sp,
                    ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(dims.space20))
                Text(
                    text = strings(Strings.login_desktop_body),
                    color = colors.onBrand.copy(alpha = 0.70f),
                    style = AromexTheme.typography.body.copy(fontSize = 15.sp),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Bottom: Humble-solutions pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(dims.radiusPill))
                    .background(colors.onBrand.copy(alpha = 0.10f))
                    .padding(horizontal = dims.space16, vertical = dims.space8)
                    .defaultMinSize(minHeight = 28.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.success),
                )
                Spacer(Modifier.width(dims.space8))
                Text(
                    text = strings(Strings.login_desktop_product_badge),
                    color = colors.onBrand.copy(alpha = 0.90f),
                    style = AromexTheme.typography.hint.copy(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
