import SwiftUI

/// Full Aromex color palette per `docs/brand-kit.md`. Feature views reach it
/// via `\.aromexColors` from the environment (or the `AromexTheme` helper),
/// not by defining `Color(red:green:blue:)` inline.
struct AromexColors {
    // Brand
    let brand: Color
    let brandPressed: Color
    let brandDeep: Color
    let brandHover: Color
    let brandTint: Color
    let brandFocusFill: Color

    // Semantic
    let success: Color
    let warning: Color
    let error: Color

    // Neutrals / surfaces
    let background: Color
    let surface: Color
    let surfaceAlt: Color
    let border: Color
    let textPrimary: Color
    let textSecondary: Color
    let textTertiary: Color
    let disabled: Color
    let onBrand: Color

    // Header gradient stops
    let headerGradientStart: Color
    let headerGradientEnd: Color
    let headerDecoration: Color

    static let light = AromexColors(
        brand: Color(hex: 0x40548A),
        brandPressed: Color(hex: 0x33477A),
        brandDeep: Color(hex: 0x283A63),
        brandHover: Color(hex: 0x4E63A0),
        brandTint: Color(hex: 0xE4E9F4),
        brandFocusFill: Color(hex: 0xF1F4FA),

        success: Color(hex: 0x2E9E66),
        warning: Color(hex: 0xCC6633),
        error: Color(hex: 0xD64545),

        background: Color(hex: 0xF5F6FA),
        surface: Color(hex: 0xFFFFFF),
        surfaceAlt: Color(hex: 0xEEF1F7),
        border: Color(hex: 0xE3E7F0),
        textPrimary: Color(hex: 0x1A1F2B),
        textSecondary: Color(hex: 0x5A6172),
        textTertiary: Color(hex: 0x99A0B0),
        disabled: Color(hex: 0xC7CCD8),
        onBrand: .white,

        headerGradientStart: Color(hex: 0x40548A),
        headerGradientEnd: Color(hex: 0x283A63),
        headerDecoration: Color.white.opacity(0.2)
    )

    static let dark = AromexColors(
        brand: Color(hex: 0x7E92C9),
        brandPressed: Color(hex: 0x6A7EB6),
        brandDeep: Color(hex: 0x95A6D8),
        brandHover: Color(hex: 0x8FA1D0),
        brandTint: Color(hex: 0x232B44),
        brandFocusFill: Color(hex: 0x1E2537),

        success: Color(hex: 0x4FB884),
        warning: Color(hex: 0xE0834B),
        error: Color(hex: 0xE86A6A),

        background: Color(hex: 0x12151C),
        surface: Color(hex: 0x1B1F2A),
        surfaceAlt: Color(hex: 0x232735),
        border: Color(hex: 0x2E3345),
        textPrimary: Color(hex: 0xF2F4F8),
        textSecondary: Color(hex: 0xB4BAC7),
        textTertiary: Color(hex: 0x7D8494),
        disabled: Color(hex: 0x3D4353),
        onBrand: .white,

        headerGradientStart: Color(hex: 0x283A63),
        headerGradientEnd: Color(hex: 0x12151C),
        headerDecoration: Color.white.opacity(0.2)
    )
}

extension Color {
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b)
    }
}
