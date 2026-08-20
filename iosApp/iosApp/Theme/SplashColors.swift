import SwiftUI

enum SplashColors {
    static func gradientStart(for scheme: ColorScheme) -> Color {
        switch scheme {
        case .dark:
            return Color(red: 0x1E / 255, green: 0x3A / 255, blue: 0x5F / 255)
        default:
            return Color(red: 0x40 / 255, green: 0x54 / 255, blue: 0x8A / 255)
        }
    }

    static func gradientEnd(for scheme: ColorScheme) -> Color {
        switch scheme {
        case .dark:
            return Color(red: 0x0F / 255, green: 0x1E / 255, blue: 0x33 / 255)
        default:
            return Color(red: 0x33 / 255, green: 0x47 / 255, blue: 0x7A / 255)
        }
    }

    static func background(for scheme: ColorScheme) -> Color {
        return gradientStart(for: scheme)
    }

    static func brand(for scheme: ColorScheme) -> Color {
        return .white
    }
}
