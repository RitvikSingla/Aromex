import SwiftUI
import SharedLogic

// MARK: - Avatar

/// Circular avatar showing an entity's initials on a deterministic brand-family
/// color derived from the name (so the same contact always gets the same color).
struct EntityAvatar: View {
    let name: String
    var size: CGFloat = 48
    var fontSize: CGFloat = 17

    var body: some View {
        ZStack {
            Circle().fill(Self.color(for: name))
            Text(Self.initials(from: name))
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
    }

    /// First letters of up to the first two words, uppercased. Falls back to "?".
    static func initials(from name: String) -> String {
        let parts = name
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: " ")
            .prefix(2)
        let letters = parts.compactMap { $0.first }.map(String.init)
        let joined = letters.joined().uppercased()
        return joined.isEmpty ? "?" : joined
    }

    /// Deterministic color from a fixed palette (matches the mock's varied avatars).
    static func color(for name: String) -> Color {
        let palette: [UInt32] = [
            0x40548A, // brand blue
            0x9B5DE5, // purple
            0xB5652E, // brown/orange
            0x2E9E66, // green
            0xC94F7C, // pink
            0x3B7FB0, // teal-blue
        ]
        var hash: UInt32 = 5381
        for scalar in name.unicodeScalars {
            hash = (hash &* 33) &+ scalar.value
        }
        return Color(hex: palette[Int(hash % UInt32(palette.count))])
    }
}

// MARK: - Role glyphs

/// Small role glyphs shown under a contact's name (person = customer,
/// shippingbox = supplier). Middleman uses an arrow-triangle glyph.
struct EntityRoleGlyphs: View {
    let roles: Set<EntityRole>
    var color: Color

    var body: some View {
        HStack(spacing: 6) {
            if roles.contains(.customer) {
                RoleChip(system: "person.fill", tint: color)
            }
            if roles.contains(.supplier) {
                RoleChip(system: "shippingbox.fill", tint: color)
            }
            if roles.contains(.middleman) {
                RoleChip(system: "arrow.triangle.swap", tint: color)
            }
        }
    }

    private struct RoleChip: View {
        let system: String
        let tint: Color
        var body: some View {
            Image(systemName: system)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 18, height: 18)
                .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 5))
        }
    }
}

// MARK: - Balance display model

/// Maps an optional live `EntityBalance` to a signed money string + semantic
/// color + a short caption key. Money is rendered from the decimal string via
/// `MoneyFormat` — never parsed to Double.
struct EntityBalanceDisplay {
    let amount: String        // e.g. "+$1240.00"
    let color: Color
    let captionKey: String    // Strings key for "to receive" / "to give" / "settled"

    static func make(_ balance: EntityBalance?, currency: String, colors: AromexColors) -> EntityBalanceDisplay {
        guard let balance = balance else {
            return EntityBalanceDisplay(
                amount: MoneyFormat.shared.format(amount: "0.00", currency: currency),
                color: colors.textTertiary,
                captionKey: Strings.shared.entities_row_settled
            )
        }
        switch balance.direction {
        case .receivable:
            return EntityBalanceDisplay(
                amount: MoneyFormat.shared.formatSigned(absoluteAmount: balance.net, currency: currency, positive: true, negative: false),
                color: colors.success,
                captionKey: Strings.shared.entities_row_to_receive
            )
        case .credit:
            return EntityBalanceDisplay(
                amount: MoneyFormat.shared.format(amount: balance.net, currency: currency),
                color: colors.error,
                captionKey: Strings.shared.entities_row_to_give
            )
        default:
            return EntityBalanceDisplay(
                amount: MoneyFormat.shared.format(amount: balance.net, currency: currency),
                color: colors.textTertiary,
                captionKey: Strings.shared.entities_row_settled
            )
        }
    }
}

// MARK: - Section header with rule

/// Eyebrow-style uppercase section title with a trailing horizontal rule —
/// the "CONTACT INFO" / "TRANSACTIONS" headers in the mocks.
struct EntitySectionHeader: View {
    let title: String
    var color: Color
    @Environment(\.aromexColors) private var colors

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .tracking(1)
                .foregroundStyle(color)
                .fixedSize(horizontal: true, vertical: false)
            Rectangle()
                .fill(colors.border)
                .frame(height: 1)
        }
    }
}
