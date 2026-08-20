package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.ui.theme.AromexTheme

@Composable
fun AromexMark(
    modifier: Modifier = Modifier,
    size: Dp = AromexTheme.dimensions.markSize,
    foreground: Color = AromexTheme.colors.onBrand,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx())
        val cornerRadius = this.size.width * 0.22f
        val squareInset = strokeWidth.toPx() / 2f
        drawRoundRect(
            color = foreground,
            topLeft = Offset(squareInset, squareInset),
            size = Size(
                width = this.size.width - squareInset * 2,
                height = this.size.height - squareInset * 2,
            ),
            cornerRadius = CornerRadius(cornerRadius),
            style = stroke,
        )

        val padX = this.size.width * 0.22f
        val padTop = this.size.height * 0.22f
        val padBottom = this.size.height * 0.18f
        val apex = Offset(this.size.width / 2f, padTop)
        val bottomLeft = Offset(padX, this.size.height - padBottom)
        val bottomRight = Offset(this.size.width - padX, this.size.height - padBottom)

        val triangle = Path().apply {
            moveTo(apex.x, apex.y)
            lineTo(bottomLeft.x, bottomLeft.y)
            lineTo(bottomRight.x, bottomRight.y)
            close()
        }
        drawPath(triangle, color = foreground, style = stroke)

        val crossbarY = this.size.height * 0.66f
        val crossbarLeftX = this.size.width * 0.34f
        val crossbarRightX = this.size.width * 0.66f
        drawLine(
            color = foreground,
            start = Offset(crossbarLeftX, crossbarY),
            end = Offset(crossbarRightX, crossbarY),
            strokeWidth = strokeWidth.toPx(),
        )
    }
}
