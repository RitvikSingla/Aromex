package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * A themed two-action dialog matching the Aromex design system — used for the
 * unsaved-changes guard, archive confirmation, and the offline/no-connection
 * prompt (#37). Kept generic so those flows share one branded surface instead
 * of raw Material `AlertDialog`s.
 *
 * [destructive] tints the confirm action red (archive/discard); otherwise it's
 * brand blue. Either button may be omitted by passing a null label.
 */
@Composable
fun AromexDialog(
    title: String,
    message: String,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(dimensions.radiusCard),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(dimensions.space24)) {
                Text(
                    text = title,
                    style = AromexTheme.typography.sectionTitle,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(dimensions.space12))
                Text(
                    text = message,
                    style = AromexTheme.typography.body,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(dimensions.space24))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissLabel != null) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = dismissLabel,
                                style = AromexTheme.typography.button,
                                color = colors.textSecondary,
                            )
                        }
                        Spacer(Modifier.width(dimensions.space8))
                    }
                    if (confirmLabel != null) {
                        TextButton(onClick = onConfirm) {
                            Text(
                                text = confirmLabel,
                                style = AromexTheme.typography.button,
                                color = if (destructive) colors.error else colors.brand,
                            )
                        }
                    }
                }
            }
        }
    }
}
