package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * Outlined secondary button — white surface, 1.5px brand-border, brand text.
 * Wraps Material 3's [OutlinedButton] so it inherits ripple + accessibility.
 */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.buttonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(dimensions.radiusButton),
        border = BorderStroke(dimensions.borderField, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface,
            contentColor = colors.textPrimary,
            disabledContentColor = colors.textTertiary,
        ),
    ) {
        Text(text = label, style = AromexTheme.typography.button)
    }
}
