package com.humblesolutions.aromex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Aromex uses **Inter** per `docs/brand-kit.md`. For now we use the platform
 * sans-serif (`FontFamily.SansSerif` → Roboto on Android) which has similar
 * geometry; a follow-up ticket swaps in bundled Inter TTFs across all three
 * platforms in one pass so the switch is atomic.
 * TODO(#21-followup): bundle Inter Regular / Medium / SemiBold / Bold in
 * androidApp/src/main/res/font/ and iosApp/iosApp/Fonts/, then flip
 * [InterFamily] here to a `FontFamily(Font(R.font.inter_regular), …)`.
 */
private val InterFamily: FontFamily = FontFamily.SansSerif

/**
 * Named typography styles matching the brand-kit table. Screens use these
 * via [LocalAromexTypography] rather than defining their own [TextStyle]s.
 */
data class AromexTypography(
    /** App display / brand mark — uppercase + tracked. */
    val display: TextStyle,
    /** Screen title, e.g. "Welcome back". */
    val screenTitle: TextStyle,
    /** Section title (e.g. "Account balances"). */
    val sectionTitle: TextStyle,
    /** Body copy. */
    val body: TextStyle,
    /** Emphasized body. */
    val bodyStrong: TextStyle,
    /** Buttons + inline labels. */
    val button: TextStyle,
    /** Uppercase field label (`EMAIL ADDRESS`). */
    val fieldLabel: TextStyle,
    /** Hint / caption / error text below fields. */
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

/** Map our typography onto Material 3's Typography so any Material component picks Inter automatically. */
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
