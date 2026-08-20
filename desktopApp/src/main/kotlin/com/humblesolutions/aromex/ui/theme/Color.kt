package com.humblesolutions.aromex.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Full Aromex color palette (tokenized per `docs/brand-kit.md`), mirroring the
 * Android palette shipped in ticket #21 so every platform reads the same
 * token names.
 */
data class AromexColors(
    // Brand
    val brand: Color,
    val brandPressed: Color,
    val brandDeep: Color,
    val brandHover: Color,
    val brandTint: Color,
    val brandFocusFill: Color,

    // Semantic
    val success: Color,
    val warning: Color,
    val error: Color,

    // Neutrals / surfaces
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val disabled: Color,
    val onBrand: Color,

    // Header gradient stops (also used by the desktop brand panel)
    val headerGradientStart: Color,
    val headerGradientEnd: Color,
    val headerDecoration: Color,
)

internal val LightAromexColors = AromexColors(
    brand = Color(0xFF40548A),
    brandPressed = Color(0xFF33477A),
    brandDeep = Color(0xFF283A63),
    brandHover = Color(0xFF4E63A0),
    brandTint = Color(0xFFE4E9F4),
    brandFocusFill = Color(0xFFF1F4FA),

    success = Color(0xFF2E9E66),
    warning = Color(0xFFCC6633),
    error = Color(0xFFD64545),

    background = Color(0xFFF5F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEEF1F7),
    border = Color(0xFFE3E7F0),
    textPrimary = Color(0xFF1A1F2B),
    textSecondary = Color(0xFF5A6172),
    textTertiary = Color(0xFF99A0B0),
    disabled = Color(0xFFC7CCD8),
    onBrand = Color(0xFFFFFFFF),

    headerGradientStart = Color(0xFF40548A),
    headerGradientEnd = Color(0xFF283A63),
    headerDecoration = Color(0x33FFFFFF),
)

internal val DarkAromexColors = AromexColors(
    brand = Color(0xFF7E92C9),
    brandPressed = Color(0xFF6A7EB6),
    brandDeep = Color(0xFF95A6D8),
    brandHover = Color(0xFF8FA1D0),
    brandTint = Color(0xFF232B44),
    brandFocusFill = Color(0xFF1E2537),

    success = Color(0xFF4FB884),
    warning = Color(0xFFE0834B),
    error = Color(0xFFE86A6A),

    background = Color(0xFF12151C),
    surface = Color(0xFF1B1F2A),
    surfaceAlt = Color(0xFF232735),
    border = Color(0xFF2E3345),
    textPrimary = Color(0xFFF2F4F8),
    textSecondary = Color(0xFFB4BAC7),
    textTertiary = Color(0xFF7D8494),
    disabled = Color(0xFF3D4353),
    onBrand = Color(0xFFFFFFFF),

    headerGradientStart = Color(0xFF283A63),
    headerGradientEnd = Color(0xFF12151C),
    headerDecoration = Color(0x33FFFFFF),
)

/**
 * Backwards-compat shim: the existing [SplashScreen] reads [LocalSplashColors].
 * Keep the shape so we don't have to touch that file in this ticket beyond a
 * light restyle — the new theme populates this alongside [AromexColors].
 */
internal val White = Color(0xFFFFFFFF)

data class SplashColors(
    val background: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val brand: Color,
)

internal val LightSplashColors = SplashColors(
    background = LightAromexColors.headerGradientStart,
    gradientStart = LightAromexColors.headerGradientStart,
    gradientEnd = LightAromexColors.headerGradientEnd,
    brand = White,
)

internal val DarkSplashColors = SplashColors(
    background = DarkAromexColors.headerGradientStart,
    gradientStart = DarkAromexColors.headerGradientStart,
    gradientEnd = DarkAromexColors.headerGradientEnd,
    brand = White,
)
