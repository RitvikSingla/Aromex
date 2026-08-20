package com.humblesolutions.aromex.devlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.humblesolutions.aromex.ui.theme.AromexTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LevelFilter(val label: String, val matches: (String) -> Boolean) {
    ALL("All", { true }),
    INFO("Info", { it == "INFO" }),
    WARN("Warn", { it == "WARN" }),
    ERROR("Error", { it == "ERROR" }),
}

/**
 * Bottom-right pill overlaid on the app that opens the floating [LogWindow]. Shows a live
 * count of captured lines. Intentionally small and unobtrusive — it's a dev tool.
 */
@Composable
fun DevLogFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val entries by LogBus.entries.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = colors.brand,
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🐞", fontSize = 15.sp) // 🐞
            Text("Logs", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (entries.isNotEmpty()) {
                Box(
                    Modifier.background(Color.White.copy(alpha = 0.22f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) { Text(entries.size.toString(), fontSize = 11.sp) }
            }
        }
    }
}

/** The floating OS window that renders the log stream. Opened as a sibling window. */
@Composable
fun LogWindow(onClose: () -> Unit) {
    val state = rememberWindowState(width = 780.dp, height = 480.dp)
    Window(onCloseRequest = onClose, state = state, title = "Aromex — Dev Logs") {
        AromexTheme { LogPanel(onClose) }
    }
}

@Composable
private fun LogPanel(onClose: () -> Unit) {
    val colors = AromexTheme.colors
    val all by LogBus.entries.collectAsState()
    val clipboard = LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LevelFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }

    val visible = remember(all, query, filter) {
        all.filter { e ->
            filter.matches(e.level) &&
                (query.isBlank() || e.message.contains(query, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(visible.size, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier.fillMaxWidth().background(colors.surface).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dev Logs", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text("${visible.size} / ${all.size}", color = colors.textTertiary, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                LevelFilter.entries.forEach { f ->
                    val selected = f == filter
                    Surface(
                        onClick = { filter = f },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) colors.brand else colors.surfaceAlt,
                        contentColor = if (selected) Color.White else colors.textSecondary,
                    ) {
                        Text(f.label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Filter text…", color = colors.textTertiary) },
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoScroll, onCheckedChange = { autoScroll = it })
                    Text("Auto-scroll", color = colors.textSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    clipboard.setText(
                        AnnotatedString(visible.joinToString("\n") { "${it.level}  ${it.message}" }),
                    )
                }) { Text("Copy") }
                TextButton(onClick = { LogBus.clear() }) { Text("Clear") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        HorizontalDivider(color = colors.border)

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No log lines yet.", color = colors.textTertiary, fontSize = 13.sp)
            }
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(visible, key = { it.seq }) { entry ->
                        val levelColor = when (entry.level) {
                            "ERROR" -> colors.error
                            "WARN" -> colors.warning
                            else -> colors.textSecondary
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                timeFmt.format(Date(entry.timeMs)),
                                color = colors.textTertiary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.level.padEnd(5),
                                color = levelColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.message,
                                color = colors.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
