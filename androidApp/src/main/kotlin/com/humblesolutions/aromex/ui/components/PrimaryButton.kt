package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * Filled brand button used for primary actions. Wraps Material 3's [Button]
 * so it inherits Material's ripple / focus / accessibility semantics; the
 * color/height/radius come from Aromex tokens.
 *
 * States (per ticket #21 mockups):
 *  - **Loading** — button stays **filled brand blue** (does NOT gray out),
 *    shows a small white spinner + [loadingLabel]. Input is blocked.
 *  - **Disabled** — grayed to `brandTint` with tertiary text (empty fields).
 *  - **Enabled** — filled brand, white label.
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
        // Swallow the click while loading so the button stays visually filled
        // (Material would otherwise draw the disabled state and hide the
        // spinner's "in-progress" affordance).
        onClick = { if (!loading) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.buttonHeight),
        enabled = enabled || loading, // stay filled while loading
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
