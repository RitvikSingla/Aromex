package com.humblesolutions.aromex.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing / radius / dimension tokens per `docs/brand-kit.md`.
 */
data class AromexDimensions(
    val space4: Dp = 4.dp,
    val space8: Dp = 8.dp,
    val space12: Dp = 12.dp,
    val space16: Dp = 16.dp,
    val space20: Dp = 20.dp,
    val space24: Dp = 24.dp,
    val space32: Dp = 32.dp,
    val space40: Dp = 40.dp,
    val space48: Dp = 48.dp,

    val radiusField: Dp = 10.dp,
    val radiusButton: Dp = 12.dp,
    val radiusCard: Dp = 16.dp,
    val radiusHeader: Dp = 20.dp,
    val radiusPill: Dp = 999.dp,

    val fieldHeight: Dp = 52.dp,
    val buttonHeight: Dp = 52.dp,
    val markSize: Dp = 40.dp,

    val screenPadding: Dp = 20.dp,
    val fieldGap: Dp = 16.dp,

    val borderThin: Dp = 1.dp,
    val borderField: Dp = 1.5.dp,
    val borderFieldFocused: Dp = 2.dp,

    /** Width at/above which the desktop login shows the two-pane split. */
    val desktopTwoPaneBreakpoint: Dp = 800.dp,

    /** Max width of the centered form column on desktop. */
    val desktopFormMaxWidth: Dp = 400.dp,
)
