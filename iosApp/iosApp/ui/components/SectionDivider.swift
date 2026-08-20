import SwiftUI

/// A hairline row with a centered label sitting on top of it — the
/// "Need access?" separator on Login.
struct SectionDivider: View {
    let label: String

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    var body: some View {
        ZStack {
            Rectangle()
                .fill(colors.border)
                .frame(height: dimensions.borderThin)
            Text(label)
                .font(typography.hint)
                .foregroundStyle(colors.textTertiary)
                .padding(.horizontal, dimensions.space12)
                .background(colors.background)
        }
        .frame(height: 20)
    }
}
