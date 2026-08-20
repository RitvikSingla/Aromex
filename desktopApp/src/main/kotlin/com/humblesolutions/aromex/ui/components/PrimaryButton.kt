package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * Filled brand button — height 52, radius 12, pointer-cursor on hover.
 * Loading state stays brand-blue with a white spinner + label (no gray-out),
 * matching #21's Android/iOS behavior.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = label,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions

    Button(
        onClick = { if (!loading) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.buttonHeight)
            .pointerHoverIcon(PointerIcon.Hand),
        enabled = enabled || loading,
        shape = RoundedCornerShape(dimensions.radiusButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.brand,
            contentColor = colors.onBrand,
            disabledContainerColor = colors.brandTint,
            disabledContentColor = colors.textTertiary,
        ),
    ) {
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.onBrand,
                )
                Spacer(Modifier.width(dimensions.space8))
                Text(text = loadingLabel, style = AromexTheme.typography.button)
            }
        } else {
            Text(text = label, style = AromexTheme.typography.button)
        }
    }
}
