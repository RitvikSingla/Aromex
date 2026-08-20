import SwiftUI

/// Outlined secondary button — white surface, 1.5px border, brand text.
/// Wraps SwiftUI's native `Button`.
struct SecondaryButton: View {
    let label: String
    let action: () -> Void
    var enabled: Bool = true

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(typography.button)
                .frame(maxWidth: .infinity)
                .frame(height: dimensions.buttonHeight)
                .foregroundStyle(colors.textPrimary)
                .background(
                    RoundedRectangle(cornerRadius: dimensions.radiusButton)
                        .fill(colors.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: dimensions.radiusButton)
                        .stroke(colors.border, lineWidth: dimensions.borderField)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
