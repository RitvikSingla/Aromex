package com.humblesolutions.aromex.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The full Aromex color palette, tokenized per `docs/brand-kit.md`.
 *
 * All colors used anywhere in the app must go through [AromexColors] via
 * [LocalAromexColors]; there are no raw hex literals in feature code. Both
 * light and dark themes are materialized here — the same token names resolve
 * to different hex values depending on `isSystemInDarkTheme()`.
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

    // Header gradient stops
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
