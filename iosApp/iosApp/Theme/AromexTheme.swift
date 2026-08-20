import SwiftUI

/// Environment values that expose the Aromex design system to every view.
/// Views pull `\.aromexColors` etc. from the environment rather than
/// referencing `AromexColors.light` directly, so dark-mode dispatch happens
/// automatically at the root and every consumer stays trivial.
private struct AromexColorsKey: EnvironmentKey {
    static let defaultValue: AromexColors = .light
}
private struct AromexTypographyKey: EnvironmentKey {
    static let defaultValue: AromexTypography = .default
}
private struct AromexDimensionsKey: EnvironmentKey {
    static let defaultValue: AromexDimensions = .default
}

extension EnvironmentValues {
    var aromexColors: AromexColors {
        get { self[AromexColorsKey.self] }
        set { self[AromexColorsKey.self] = newValue }
    }
    var aromexTypography: AromexTypography {
        get { self[AromexTypographyKey.self] }
        set { self[AromexTypographyKey.self] = newValue }
    }
    var aromexDimensions: AromexDimensions {
        get { self[AromexDimensionsKey.self] }
        set { self[AromexDimensionsKey.self] = newValue }
    }
}

/// Root wrapper: reads the current `\.colorScheme`, picks light/dark tokens,
/// injects them into the environment for all children.
struct AromexTheme<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    @ViewBuilder let content: () -> Content

    var body: some View {
        let colors: AromexColors = scheme == .dark ? .dark : .light
        content()
            .environment(\.aromexColors, colors)
            .environment(\.aromexTypography, .default)
            .environment(\.aromexDimensions, .default)
    }
}
