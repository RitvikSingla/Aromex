package com.humblesolutions.aromex.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.humblesolutions.aromex.ui.theme.AromexTheme

@Composable
fun AromexDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = AromexTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = AromexTheme.typography.sectionTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            Text(
                text = message,
                style = AromexTheme.typography.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    style = AromexTheme.typography.button,
                    color = if (destructive) colors.error else colors.brand,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissLabel,
                    style = AromexTheme.typography.button,
                    color = colors.textSecondary,
                )
            }
        },
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
    )
}
