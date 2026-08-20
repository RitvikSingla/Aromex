package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * A hairline row with a centered label sitting on top of it — the "Need
 * access?" separator on the Login screen. Background color behind the label
 * breaks the line so it visually threads through the text.
 */
@Composable
fun SectionDivider(
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions

    Box(
        modifier = modifier.fillMaxWidth().height(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(
            color = colors.border,
            thickness = dimensions.borderThin,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = label,
            style = AromexTheme.typography.hint,
            color = colors.textTertiary,
            modifier = Modifier
                .background(colors.background)
                .padding(horizontal = dimensions.space12),
        )
    }
}
