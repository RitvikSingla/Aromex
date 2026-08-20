package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlinx.coroutines.delay

/**
 * "Updated 2m ago ⟳" — the balance freshness control shared by Contacts and Money.
 *
 * Balances come from Humble Ledger on request rather than a live stream, so without saying when
 * they were read the screen invites you to trust a number of unknown age. Showing the age and
 * offering the re-read is what makes that honest.
 *
 * The label re-renders on a ticker, so "just now" becomes "1m ago" while you're looking at it
 * instead of only when something else happens to recompose.
 */
@Composable
fun BalanceFreshness(
    refreshedAt: Long?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val shape = RoundedCornerShape(dims.radiusPill)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    // Re-read the clock every 30s so the age stays truthful without a recomposition storm.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(refreshedAt) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Row(
        modifier = modifier
            .height(30.dp)
            // clip() before clickable so the hover highlight follows the rounded corners.
            .clip(shape)
            .hoverable(src)
            .background(if (hovered && !isLoading) colors.surfaceAlt else Color.Transparent)
            .border(1.dp, colors.border, shape)
            .then(
                if (isLoading) Modifier
                else Modifier.clickable(onClick = onRefresh).pointerHoverIcon(PointerIcon.Hand),
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.brand)
        } else {
            Icon(Icons.Filled.Refresh, null, tint = colors.brand, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (isLoading) strings(Strings.balances_updating) else freshnessLabel(refreshedAt, now),
            style = AromexTheme.typography.hint,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * How old the numbers are, in words. Deliberately vague past an hour — the useful distinction is
 * "current" versus "old enough to re-read", not the exact minute.
 */
@Composable
private fun freshnessLabel(refreshedAt: Long?, now: Long): String {
    if (refreshedAt == null) return strings(Strings.balances_not_loaded)
    val minutes = ((now - refreshedAt).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1L -> strings(Strings.balances_just_now)
        minutes < 60L -> strings(Strings.balances_minutes_ago, minutes)
        else -> strings(Strings.balances_hours_ago, minutes / 60)
    }
}
