package com.humblesolutions.aromex.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing / radius / dimension tokens per `docs/brand-kit.md`. Feature code
 * reads these via [LocalAromexDimensions] rather than hard-coding dp values,
 * so the whole app scales consistently.
 */
data class AromexDimensions(
    // 4pt base scale
    val space4: Dp = 4.dp,
    val space8: Dp = 8.dp,
    val space12: Dp = 12.dp,
    val space16: Dp = 16.dp,
    val space20: Dp = 20.dp,
    val space24: Dp = 24.dp,
    val space32: Dp = 32.dp,
    val space40: Dp = 40.dp,
    val space48: Dp = 48.dp,

    // Radii
    val radiusField: Dp = 10.dp,
    val radiusButton: Dp = 12.dp,
    val radiusCard: Dp = 16.dp,
    val radiusHeader: Dp = 20.dp,

    // Heights
    val fieldHeight: Dp = 52.dp,
    val buttonHeight: Dp = 52.dp,
    val markSize: Dp = 40.dp,

    // Screen padding
    val screenPadding: Dp = 20.dp,

    // Between-field vertical gap
    val fieldGap: Dp = 16.dp,

    // Border thicknesses
    val borderThin: Dp = 1.dp,
    val borderField: Dp = 1.5.dp,
    val borderFieldFocused: Dp = 2.dp,
)
