package com.humblesolutions.aromex.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme

/** Top-level section currently visible in the desktop shell. */
enum class DesktopSection { ENTITIES, INVENTORY, STOCK_HISTORY, SALES, SALES_HISTORY, MONEY, COMMISSION_RULES, SETTINGS }

internal data class NavDef(val icon: ImageVector, val label: String, val active: Boolean)

internal val SidebarBg = Color(0xFF1B2B48)

/**
 * Collapsed rail width — also the left inset the main content reserves so the sidebar can
 * expand as an *overlay* on top of the content instead of pushing it. Keep these two in
 * lockstep: content padding start == collapsed rail width.
 */
internal val CollapsedSidebarWidth = 72.dp
internal val ExpandedSidebarWidth = 280.dp

/**
 * Hover-expand sidebar shared by all desktop sections. Pass [selectedSection] to
 * highlight the active item; [onNavigateToEntities] and [onNavigateToInventory] fire
 * when the user clicks a nav item.
 */
@Composable
internal fun NavSidebar(
    expanded: Boolean,
    width: Dp,
    selectedSection: DesktopSection,
    session: UserSession?,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit = {},
    /** Money movement (ticket #90). Defaulted so a screen that hasn't wired it still compiles. */
    onNavigateToMoney: () -> Unit = {},
    /** Company settings (M10). Defaulted so a screen that hasn't wired it still compiles. */
    onNavigateToSettings: () -> Unit = {},
    /** Commission rules (ticket #97) — admin-only settings. Defaulted for the same reason. */
    onNavigateToCommissionRules: () -> Unit = {},
    /** Stock history (ticket #106) — every Add-Inventory batch. Defaulted for the same reason. */
    onNavigateToStockHistory: () -> Unit = {},
    onSignOutRequest: () -> Unit,
) {
    val dims = AromexTheme.dimensions

    val canViewSales = session != null && session.permissions.sales != PermissionLevel.NONE
    val canViewMoney = session != null && session.permissions.transactions != PermissionLevel.NONE
    val isAdmin = session != null && session.role == com.humblesolutions.aromex.model.UserRole.ADMIN

    val navItems: List<Pair<NavDef, () -> Unit>> = buildList {
        add(NavDef(Icons.Outlined.Dashboard, strings(Strings.entities_sidebar_dashboard), false) to {})
        add(NavDef(Icons.Outlined.People, strings(Strings.entities_list_title), selectedSection == DesktopSection.ENTITIES) to onNavigateToEntities)
        if (canViewSales) {
            add(NavDef(Icons.Outlined.CreditCard, strings(Strings.entities_sidebar_sales), selectedSection == DesktopSection.SALES) to onNavigateToSales)
            add(NavDef(Icons.Outlined.ReceiptLong, strings(Strings.sales_history_sidebar), selectedSection == DesktopSection.SALES_HISTORY) to onNavigateToSalesHistory)
        }
        add(NavDef(Icons.Outlined.Inventory, strings(Strings.entities_sidebar_inventory), selectedSection == DesktopSection.INVENTORY) to onNavigateToInventory)
        // Sits directly under Inventory: it answers "where did this stock come from", which is
        // the question people ask while looking at the stock itself.
        add(NavDef(Icons.Outlined.History, strings(Strings.stock_history_sidebar), selectedSection == DesktopSection.STOCK_HISTORY) to onNavigateToStockHistory)
        if (canViewMoney) {
            add(NavDef(Icons.Outlined.AccountBalanceWallet, strings(Strings.money_title), selectedSection == DesktopSection.MONEY) to onNavigateToMoney)
        }
        if (isAdmin) {
            add(NavDef(Icons.Outlined.Payments, strings(Strings.commission_rules_sidebar), selectedSection == DesktopSection.COMMISSION_RULES) to onNavigateToCommissionRules)
        }
        add(NavDef(Icons.Outlined.BarChart, strings(Strings.entities_sidebar_reports), false) to {})
    }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            // Depth cue so the expanded rail reads as floating over the content it overlaps.
            .shadow(if (expanded) 16.dp else 0.dp)
            .background(SidebarBg)
            .hoverable(interactionSource),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(dims.space20))
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF40548A)),
            contentAlignment = Alignment.Center,
        ) {
            AromexMark(size = 26.dp, foreground = Color.White)
        }
        Spacer(Modifier.height(dims.space32))
        navItems.forEach { (item, onClick) ->
            SidebarNavItem(item = item, expanded = expanded, onClick = onClick)
            Spacer(Modifier.height(dims.space4))
        }
        Spacer(Modifier.weight(1f))
        SidebarNavItem(
            item = NavDef(
                Icons.Filled.Settings,
                strings(Strings.entities_sidebar_settings),
                selectedSection == DesktopSection.SETTINGS,
            ),
            expanded = expanded,
            onClick = onNavigateToSettings,
        )
        Spacer(Modifier.height(dims.space16))
        val displayName = session?.displayName?.takeIf { it.isNotBlank() } ?: session?.email ?: "U"
        Box(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onSignOutRequest)
                .padding(dims.space4),
        ) {
            Avatar(name = displayName, size = 36.dp)
        }
        Spacer(Modifier.height(dims.space20))
    }
}

@Composable
internal fun SidebarNavItem(item: NavDef, expanded: Boolean, onClick: () -> Unit) {
    val dims = AromexTheme.dimensions
    val hoverSrc = remember { MutableInteractionSource() }
    val isHovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dims.space12)
            .clip(RoundedCornerShape(dims.radiusField))
            .background(
                when {
                    item.active -> Color.White.copy(alpha = 0.15f)
                    isHovered -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hoverSrc)
            .let { mod ->
                if (!item.active) {
                    mod.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                } else {
                    mod.pointerHoverIcon(PointerIcon.Default)
                }
            }
            .padding(horizontal = dims.space12, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (item.active) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandHorizontally(tween(200)),
            exit = fadeOut(tween(100)) + shrinkHorizontally(tween(200)),
        ) {
            Text(
                text = item.label,
                style = AromexTheme.typography.button,
                color = if (item.active) Color.White else Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = dims.space12),
            )
        }
    }
}
