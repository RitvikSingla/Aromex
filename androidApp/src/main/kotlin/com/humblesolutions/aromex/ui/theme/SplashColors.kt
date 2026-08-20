package com.humblesolutions.aromex.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Legacy splash color holder. Kept as a thin adapter so [SplashScreen] keeps
 * compiling during the ticket #21 restyle; [AromexTheme] fills it from the
 * canonical header-gradient tokens on [AromexColors]. New code should use
 * [AromexTheme.colors] directly.
 */
data class SplashColors(
    val background: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val brand: Color,
)

val LocalSplashColors = staticCompositionLocalOf {
    SplashColors(
        background = LightAromexColors.headerGradientStart,
        gradientStart = LightAromexColors.headerGradientStart,
        gradientEnd = LightAromexColors.headerGradientEnd,
        brand = LightAromexColors.onBrand,
    )
}
