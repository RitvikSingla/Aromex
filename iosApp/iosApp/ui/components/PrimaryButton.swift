import SwiftUI

/// Filled brand button for primary actions. Wraps SwiftUI's native `Button`.
///
/// States (per ticket #21 mockups):
///  - **Loading** — button stays **filled brand blue** (does NOT gray out),
///    shows a spinner + label. Input is blocked.
///  - **Disabled** — filled `brand.opacity(0.5)` for a visible but muted
///    state. We deliberately do NOT use SwiftUI's `.disabled()` modifier —
///    it applies an additional ~30% opacity that makes the button
///    disappear against the light-grey surface. Blocking clicks in the
///    action closure gives us full control over the visual weight.
///  - **Enabled** — filled brand, white label.
struct PrimaryButton: View {
    let label: String
    var loadingLabel: String?
    let action: () -> Void
    var enabled: Bool = true
    var loading: Bool = false

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    private var interactive: Bool { enabled && !loading }

    var body: some View {
        Button(action: {
            if interactive { action() }
        }) {
            HStack(spacing: dimensions.space8) {
                if loading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(colors.onBrand)
                        .controlSize(.small)
                    Text(loadingLabel ?? label)
                        .font(typography.button)
                } else {
                    Text(label).font(typography.button)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: dimensions.buttonHeight)
            .foregroundStyle(colors.onBrand)
            .background(
                RoundedRectangle(cornerRadius: dimensions.radiusButton)
                    // Loading + enabled → full brand. Disabled → half-brand
                    // (still readable, matches Android's Material disabled
                    // appearance better than the ultra-light brandTint).
                    .fill(interactive || loading ? colors.brand : colors.brand.opacity(0.5))
            )
        }
        .buttonStyle(.plain)
        // No .disabled() — see the doc-comment above. Clicks are blocked
        // in the action closure.
    }
}
