import SwiftUI

/// Aromex mark — outlined rounded-square with an outlined triangle "A"
/// inside. Drawn in code so it scales crisply and inherits the current
/// foreground color, whether that's white on a blue header or the brand
/// blue on a light surface.
struct AromexMark: View {
    var size: CGFloat = 40
    var foreground: Color = .white
    var strokeWidth: CGFloat = 2

    var body: some View {
        Canvas { context, canvasSize in
            let width = canvasSize.width
            let height = canvasSize.height
            let cornerRadius = width * 0.22
            let inset = strokeWidth / 2

            // Outer rounded square
            let rect = CGRect(
                x: inset,
                y: inset,
                width: width - inset * 2,
                height: height - inset * 2
            )
            let squarePath = Path(roundedRect: rect, cornerRadius: cornerRadius)
            context.stroke(squarePath, with: .color(foreground), lineWidth: strokeWidth)

            // Inner triangle "A"
            let padX = width * 0.22
            let padTop = height * 0.22
            let padBottom = height * 0.18
            let apex = CGPoint(x: width / 2, y: padTop)
            let bottomLeft = CGPoint(x: padX, y: height - padBottom)
            let bottomRight = CGPoint(x: width - padX, y: height - padBottom)
            var triangle = Path()
            triangle.move(to: apex)
            triangle.addLine(to: bottomLeft)
            triangle.addLine(to: bottomRight)
            triangle.closeSubpath()
            context.stroke(triangle, with: .color(foreground), lineWidth: strokeWidth)

            // Cross-bar of the "A"
            let crossbarY = height * 0.66
            var crossbar = Path()
            crossbar.move(to: CGPoint(x: width * 0.34, y: crossbarY))
            crossbar.addLine(to: CGPoint(x: width * 0.66, y: crossbarY))
            context.stroke(crossbar, with: .color(foreground), lineWidth: strokeWidth)
        }
        .frame(width: size, height: size)
    }
}
