import SwiftUI
import Network
import SharedLogic

// MARK: - Connectivity

/// Lightweight reachability monitor used to gate pull-to-refresh: when the user
/// pulls with no internet we surface the themed offline dialog instead of firing
/// a doomed network call. Presentation-only — no shared logic touched.
@MainActor
final class Connectivity: ObservableObject {
    @Published private(set) var isOnline: Bool = true

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "aromex.connectivity")

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.isOnline = online }
        }
        monitor.start(queue: queue)
    }

    deinit { monitor.cancel() }
}

// MARK: - Country flag

enum CountryFlag {
    /// Regional-indicator flag emoji from an ISO-3166 alpha-2 code (e.g. "CA" → 🇨🇦).
    static func emoji(iso: String) -> String {
        let base: UInt32 = 0x1F1E6
        var result = ""
        for scalar in iso.uppercased().unicodeScalars {
            guard scalar.value >= 65, scalar.value <= 90 else { return "🏳️" }
            if let flag = UnicodeScalar(base + (scalar.value - 65)) {
                result.unicodeScalars.append(flag)
            }
        }
        return result.isEmpty ? "🏳️" : result
    }
}

// MARK: - Country picker (searchable)

/// A searchable list of `Countries.shared.all`, filtered by name or dial code.
/// Presented as a sheet from the phone row; the selection is stored in the
/// caller's `@State` (presentation-only — the VM's number field is unchanged).
struct CountryPickerView: View {
    @Binding var selection: Country
    let onDismiss: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    @State private var query: String = ""

    private var results: [Country] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty { return Countries.shared.all }
        return Countries.shared.all.filter {
            $0.name.lowercased().contains(q) || $0.dialCode.lowercased().contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                colors.background.ignoresSafeArea()
                Group {
                    if results.isEmpty {
                        VStack(spacing: dimensions.space12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 36, weight: .light))
                                .foregroundStyle(colors.textTertiary)
                            Text(loc.t(Strings.shared.country_picker_empty))
                                .font(typography.body)
                                .foregroundStyle(colors.textSecondary)
                        }
                    } else {
                        List {
                            ForEach(results, id: \.iso) { country in
                                Button {
                                    selection = country
                                    onDismiss()
                                } label: {
                                    HStack(spacing: dimensions.space12) {
                                        Text(CountryFlag.emoji(iso: country.iso))
                                            .font(.system(size: 22))
                                        Text(country.name)
                                            .font(typography.body)
                                            .foregroundStyle(colors.textPrimary)
                                            .lineLimit(1)
                                            .truncationMode(.tail)
                                        Spacer(minLength: dimensions.space8)
                                        Text(country.dialCode)
                                            .font(.system(size: 15, weight: .semibold).monospacedDigit())
                                            .foregroundStyle(colors.textSecondary)
                                        if country.iso == selection.iso {
                                            Image(systemName: "checkmark")
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundStyle(colors.brand)
                                        }
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .listRowBackground(colors.surface)
                            }
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .searchable(
                text: $query,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: loc.t(Strings.shared.country_picker_search)
            )
            .navigationTitle(loc.t(Strings.shared.country_picker_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.entity_detail_cancel)) { onDismiss() }
                }
            }
        }
    }
}

// MARK: - Themed dialog

/// A themed modal dialog matching the app's design system (used for the
/// discard-changes confirmation). Rendered as a scrim + card overlay so it
/// reads consistently with the offline dialog rather than a bare `.alert`.
struct ThemedDialog: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String
    /// Primary (often destructive) action label + handler.
    let primaryLabel: String
    let primaryDestructive: Bool
    let onPrimary: () -> Void
    /// Secondary / cancel label.
    let secondaryLabel: String

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    func body(content: Content) -> some View {
        content.overlay {
            if isPresented {
                ZStack {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .onTapGesture { isPresented = false }

                    VStack(alignment: .leading, spacing: dimensions.space16) {
                        Text(title)
                            .font(typography.sectionTitle)
                            .foregroundStyle(colors.textPrimary)
                        Text(message)
                            .font(typography.body)
                            .foregroundStyle(colors.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)

                        HStack(spacing: dimensions.space12) {
                            Button {
                                isPresented = false
                            } label: {
                                Text(secondaryLabel)
                                    .font(typography.button)
                                    .foregroundStyle(colors.textSecondary)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 44)
                                    .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
                            }
                            .buttonStyle(.plain)

                            Button {
                                isPresented = false
                                onPrimary()
                            } label: {
                                Text(primaryLabel)
                                    .font(typography.button)
                                    .foregroundStyle(.white)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 44)
                                    .background(
                                        (primaryDestructive ? colors.error : colors.brand),
                                        in: RoundedRectangle(cornerRadius: dimensions.radiusButton)
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(dimensions.space24)
                    .frame(maxWidth: 340)
                    .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
                    .padding(dimensions.space32)
                    .shadow(color: Color.black.opacity(0.25), radius: 20, x: 0, y: 8)
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: isPresented)
    }
}

/// Themed offline dialog (scrim + card), consistent with `ThemedDialog`.
struct ThemedOfflineDialog: ViewModifier {
    @Binding var isPresented: Bool
    var onRetry: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    func body(content: Content) -> some View {
        content.overlay {
            if isPresented {
                ZStack {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .onTapGesture { isPresented = false }

                    VStack(spacing: dimensions.space16) {
                        Image(systemName: "wifi.slash")
                            .font(.system(size: 34, weight: .light))
                            .foregroundStyle(colors.textTertiary)
                        Text(loc.t(Strings.shared.offline_title))
                            .font(typography.sectionTitle)
                            .foregroundStyle(colors.textPrimary)
                            .multilineTextAlignment(.center)
                        Text(loc.t(Strings.shared.offline_body))
                            .font(typography.body)
                            .foregroundStyle(colors.textSecondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)

                        HStack(spacing: dimensions.space12) {
                            Button {
                                isPresented = false
                            } label: {
                                Text(loc.t(Strings.shared.offline_dismiss))
                                    .font(typography.button)
                                    .foregroundStyle(colors.textSecondary)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 44)
                                    .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
                            }
                            .buttonStyle(.plain)

                            Button {
                                isPresented = false
                                onRetry()
                            } label: {
                                Text(loc.t(Strings.shared.offline_retry))
                                    .font(typography.button)
                                    .foregroundStyle(.white)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 44)
                                    .background(colors.brand, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(dimensions.space24)
                    .frame(maxWidth: 340)
                    .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
                    .padding(dimensions.space32)
                    .shadow(color: Color.black.opacity(0.25), radius: 20, x: 0, y: 8)
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: isPresented)
    }
}
