package com.humblesolutions.aromex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAromexColors = staticCompositionLocalOf { LightAromexColors }
val LocalAromexTypography = staticCompositionLocalOf { AromexTypographyDefaults }
val LocalAromexDimensions = staticCompositionLocalOf { AromexDimensions() }
val LocalSplashColors = staticCompositionLocalOf { LightSplashColors }

object AromexTheme {
    val colors: AromexColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAromexColors.current

    val typography: AromexTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAromexTypography.current

    val dimensions: AromexDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalAromexDimensions.current
}

@Composable
fun AromexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkAromexColors else LightAromexColors
    val typography = AromexTypographyDefaults
    val dimensions = AromexDimensions()
    val splashColors = if (darkTheme) DarkSplashColors else LightSplashColors

    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.brand,
            onPrimary = colors.onBrand,
            secondary = colors.brand,
            onSecondary = colors.onBrand,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.error,
            onError = colors.onBrand,
            errorContainer = colors.error.copy(alpha = 0.2f),
            onErrorContainer = colors.error,
        )
    } else {
        lightColorScheme(
            primary = colors.brand,
            onPrimary = colors.onBrand,
            secondary = colors.brand,
            onSecondary = colors.onBrand,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.error,
            onError = colors.onBrand,
            errorContainer = colors.error.copy(alpha = 0.12f),
            onErrorContainer = colors.error,
        )
    }

    CompositionLocalProvider(
        LocalAromexColors provides colors,
        LocalAromexTypography provides typography,
        LocalAromexDimensions provides dimensions,
        LocalSplashColors provides splashColors,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = buildMaterialTypography(typography),
            content = content,
        )
    }
}
