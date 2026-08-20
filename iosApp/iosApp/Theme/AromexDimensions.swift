import CoreGraphics

/// Spacing / radius / dimension tokens per `docs/brand-kit.md`. Kept in sync
/// with `androidApp/.../ui/theme/Dimensions.kt`.
struct AromexDimensions {
    var space4: CGFloat = 4
    var space8: CGFloat = 8
    var space12: CGFloat = 12
    var space16: CGFloat = 16
    var space20: CGFloat = 20
    var space24: CGFloat = 24
    var space32: CGFloat = 32
    var space40: CGFloat = 40
    var space48: CGFloat = 48

    var radiusField: CGFloat = 10
    var radiusButton: CGFloat = 12
    var radiusCard: CGFloat = 16
    var radiusHeader: CGFloat = 20

    var fieldHeight: CGFloat = 52
    var buttonHeight: CGFloat = 52
    var markSize: CGFloat = 40

    var screenPadding: CGFloat = 20
    var fieldGap: CGFloat = 16

    var borderThin: CGFloat = 1
    var borderField: CGFloat = 1.5
    var borderFieldFocused: CGFloat = 2

    static let `default` = AromexDimensions()
}
