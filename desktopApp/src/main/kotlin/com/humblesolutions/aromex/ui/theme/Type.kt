package com.humblesolutions.aromex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// TODO(#21-followup): swap system sans-serif for bundled Inter TTFs in one pass across all three platforms.
private val InterFamily: FontFamily = FontFamily.SansSerif

data class AromexTypography(
    val display: TextStyle,
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val button: TextStyle,
    val fieldLabel: TextStyle,
    val hint: TextStyle,
)

internal val AromexTypographyDefaults = AromexTypography(
    display = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 2.sp,
    ),
    screenTitle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    sectionTitle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    body = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyStrong = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    button = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    fieldLabel = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    hint = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

internal fun buildMaterialTypography(t: AromexTypography): Typography = Typography(
    displayLarge = t.display,
    headlineLarge = t.screenTitle,
    headlineMedium = t.screenTitle,
    titleLarge = t.sectionTitle,
    titleMedium = t.sectionTitle,
    titleSmall = t.button,
    bodyLarge = t.body,
    bodyMedium = t.body,
    bodySmall = t.hint,
    labelLarge = t.button,
    labelMedium = t.fieldLabel,
    labelSmall = t.hint,
)
