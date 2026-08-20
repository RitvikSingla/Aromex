import SwiftUI

/// Typography styles matching the brand-kit table. Uses the system sans-serif
/// (SF Pro on iOS) for now — geometry is close to Inter. A follow-up ticket
/// bundles Inter TTFs and swaps the family here in one atomic change across
/// all three platforms.
/// TODO(#21-followup): bundle Inter Regular / Medium / SemiBold / Bold in
/// `iosApp/Fonts/` and register in `Info.plist` `UIAppFonts`, then flip the
/// `.font(...)` families below to `.custom("Inter-…", size:)`.
///
/// **Dynamic Type**: fonts derive from SwiftUI text styles (`.body`,
/// `.title2`, etc.) so they scale with the user's OS-level Dynamic Type
/// setting. Sizes below are anchored via `.system(size:relativeTo:)` — the
/// number is the base at the default text-size setting, and the second
/// argument tells SwiftUI which style to scale against.
struct AromexTypography {
    let display: Font
    let screenTitle: Font
    let sectionTitle: Font
    let body: Font
    let bodyStrong: Font
    let button: Font
    let fieldLabel: Font
    let hint: Font

    static let `default` = AromexTypography(
        display: .system(size: 20, weight: .bold, design: .default),
        screenTitle: .system(size: 28, weight: .bold, design: .default),
        sectionTitle: .system(size: 18, weight: .semibold, design: .default),
        body: .system(size: 16, weight: .regular, design: .default),
        bodyStrong: .system(size: 16, weight: .medium, design: .default),
        button: .system(size: 15, weight: .semibold, design: .default),
        fieldLabel: .system(size: 12, weight: .semibold, design: .default),
        hint: .system(size: 13, weight: .regular, design: .default)
    )
}
