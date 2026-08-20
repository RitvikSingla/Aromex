package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlin.math.absoluteValue

private val avatarPalette = listOf(
    Color(0xFF40548A), // brand blue
    Color(0xFFCC6633), // warning orange
    Color(0xFF7B5EA7), // purple
    Color(0xFF2E9E66), // success green
    Color(0xFF1A6BA0), // info blue
    Color(0xFFD64545), // error red
)

private fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        words.size == 1 && words[0].length >= 2 -> words[0].substring(0, 2).uppercase()
        words.size == 1 -> words[0].first().uppercaseChar().toString()
        else -> "?"
    }
}

private fun avatarColor(name: String): Color =
    avatarPalette[name.trim().hashCode().absoluteValue % avatarPalette.size]

@Composable
fun Avatar(
    name: String,
    size: Dp = 40.dp,
    textStyle: TextStyle = AromexTheme.typography.button.copy(
        fontSize = (size.value * 0.35f).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            style = textStyle,
            color = Color.White,
        )
    }
}
