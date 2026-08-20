import SwiftUI
import SharedLogic

/// Typed navigation route for the entities flow. List → Detail, List → Add and
/// Detail → Edit are all real pushed destinations (no sheets), driven via
/// `navigationDestination(for:)` on the ancestor `NavigationStack`.
enum EntityRoute: Hashable {
    case detail(id: String)
    case add
    case edit(id: String)
}

/// Entity LIST ("Contacts") — ticket #37 real UI (round 2).
///
/// Presentation-only restyle over the existing `EntitiesViewModel`. Blue
/// gradient header (rounded bottom) with an inline search field + "+" button,
/// a two-half summary bar (TO RECEIVE / TO GIVE), and a scroll list of contact
/// cards. States: loaded / empty / loading / error. Add / Detail / Edit are
/// pushed via `navigationDestination`. Pull-to-refresh with no connectivity (or
/// a network failure) surfaces a themed offline dialog instead of refreshing.
struct EntitiesView: View {
    let session: UserSession
    let config: FirebaseClientConfig

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.dismiss) private var dismiss

    @StateObject private var viewModel = EntitiesViewModel()
    @StateObject private var connectivity = Connectivity()
    @State private var path: [EntityRoute] = []
    @State private var offlineDismissed = false
    @State private var showOfflineManual = false

    private var currency: String { session.currency }

    var body: some View {
        // EntitiesView owns the entities flow's NavigationStack with a typed path:
        // List → Detail, List → Add, Detail → Edit are all real pushed destinations
        // (no sheets). The path is @State so it survives rotation.
        NavigationStack(path: $path) {
            listRoot
                .navigationDestination(for: EntityRoute.self) { route in
                    destination(for: route)
                }
        }
    }

    @ViewBuilder
    private func destination(for route: EntityRoute) -> some View {
        switch route {
        case .detail(let id):
            if let entity = entity(for: id) {
                EntityDetailView(
                    entity: entity,
                    currency: currency,
                    viewModel: viewModel,
                    session: session,
                    config: config,
                    onEdit: { path.append(.edit(id: id)) }
                )
            }
        case .add:
            EntityFormView(
                viewModel: EntityFormViewModel(session: session, config: config, existing: nil),
                currency: currency,
                onClose: { if !path.isEmpty { path.removeLast() } },
                onDone: {
                    viewModel.loadBalances()
                    path.removeAll()
                }
            )
        case .edit(let id):
            if let entity = entity(for: id) {
                EntityFormView(
                    viewModel: EntityFormViewModel(session: session, config: config, existing: entity),
                    currency: currency,
                    onClose: { if !path.isEmpty { path.removeLast() } },
                    onDone: {
                        viewModel.loadBalances()
                        // Pop back to the detail screen after an edit.
                        if !path.isEmpty { path.removeLast() }
                    }
                )
            }
        }
    }

    private var listRoot: some View {
        ZStack(alignment: .bottomTrailing) {
            colors.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                summaryBar
                content
            }
            // Let the whole column extend under the status bar so the header's
            // gradient reaches the top edge. Applying this to the container (not
            // the header child) avoids the reserved-space gap that pushed the
            // summary bar down.
            .ignoresSafeArea(edges: .top)

            if viewModel.canManage && !viewModel.permissionDenied {
                fab
            }
        }
        .navigationBarHidden(true)
        .onAppear { viewModel.bind(session: session, config: config) }
        .alert(loc.t(Strings.shared.entities_error_title), isPresented: Binding(
            get: { viewModel.actionError != nil },
            set: { if !$0 { viewModel.actionError = nil } }
        )) {
            Button(loc.t(Strings.shared.entity_detail_cancel), role: .cancel) {}
        } message: {
            Text(viewModel.actionError ?? "")
        }
        .modifier(ThemedOfflineDialog(
            isPresented: Binding(
                get: { (showOfflineManual || showLoadOffline) && !offlineDismissed },
                set: { if !$0 { offlineDismissed = true; showOfflineManual = false } }
            ),
            onRetry: {
                offlineDismissed = false
                showOfflineManual = false
                viewModel.loadBalances()
            }
        ))
        .onChange(of: viewModel.isLoadingBalances) { loading in
            // Allow the offline dialog to reappear on a fresh failed refresh.
            if loading { offlineDismissed = false }
        }
    }

    private func entity(for id: String) -> Entity? {
        viewModel.items.first(where: { $0.id == id })?.entity
    }

    // MARK: - Header

    private var header: some View {
        let topInset = safeTop()
        return VStack(alignment: .leading, spacing: dimensions.space16) {
            HStack(alignment: .top) {
                Button { dismiss() } label: {
                    Image(systemName: "arrow.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(colors.onBrand)
                        .frame(width: 40, height: 40)
                        .background(colors.onBrand.opacity(0.18), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(loc.t(Strings.shared.entity_detail_back_cd))

                VStack(alignment: .leading, spacing: 2) {
                    Text(loc.t(Strings.shared.entities_list_eyebrow))
                        .font(.system(size: 12, weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(colors.onBrand.opacity(0.7))
                    Text(loc.t(Strings.shared.entities_list_title))
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(colors.onBrand)
                }
                Spacer()
                if viewModel.canManage && !viewModel.permissionDenied {
                    NavigationLink(value: EntityRoute.add) {
                        Image(systemName: "plus")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(colors.onBrand)
                            .frame(width: 40, height: 40)
                            .background(colors.onBrand.opacity(0.18), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(loc.t(Strings.shared.entities_add_cd))
                }
            }

            searchField
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, topInset + dimensions.space16)
        .padding(.bottom, dimensions.space20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
        // Flat / square bottom edge — no corner rounding.
    }

    private var searchField: some View {
        HStack(spacing: dimensions.space8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.onBrand.opacity(0.7))
            ZStack(alignment: .leading) {
                if viewModel.searchText.isEmpty {
                    Text(loc.t(Strings.shared.entities_search_placeholder))
                        .foregroundStyle(colors.onBrand.opacity(0.65))
                        .font(typography.body)
                }
                TextField("", text: $viewModel.searchText)
                    .foregroundStyle(colors.onBrand)
                    .font(typography.body)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
        }
        .padding(.horizontal, dimensions.space16)
        .frame(height: 48)
        .background(colors.onBrand.opacity(0.15), in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
    }

    // MARK: - Summary bar

    private var summaryBar: some View {
        let totals = balanceTotals()
        return HStack(spacing: 0) {
            summaryHalf(
                label: loc.t(Strings.shared.entities_summary_to_receive),
                amount: MoneyFormat.shared.format(amount: totals.receive, currency: currency),
                color: colors.success
            )
            Rectangle()
                .fill(colors.border)
                .frame(width: 1, height: 40)
            summaryHalf(
                label: loc.t(Strings.shared.entities_summary_to_give),
                amount: MoneyFormat.shared.format(amount: totals.give, currency: currency),
                color: colors.error
            )
        }
        .padding(.vertical, dimensions.space16)
        .background(colors.surface)
    }

    private func summaryHalf(label: String, amount: String, color: Color) -> some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .tracking(0.5)
                .foregroundStyle(colors.textTertiary)
            Text(amount)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, dimensions.space8)
    }

    // MARK: - Content states

    @ViewBuilder
    private var content: some View {
        if viewModel.permissionDenied {
            centered {
                stateBlock(
                    icon: "lock.fill",
                    title: loc.t(Strings.shared.entities_no_access),
                    cta: nil
                )
            }
        } else if viewModel.isLoadingList && viewModel.items.isEmpty {
            centered {
                VStack(spacing: dimensions.space16) {
                    ProgressView().tint(colors.brand)
                    Text(loc.t(Strings.shared.entities_loading))
                        .font(typography.body)
                        .foregroundStyle(colors.textSecondary)
                }
            }
        } else if let error = viewModel.listError, viewModel.items.isEmpty {
            centered {
                stateBlock(
                    icon: "exclamationmark.triangle.fill",
                    title: loc.t(Strings.shared.entities_error_title),
                    cta: (loc.t(Strings.shared.entities_error_retry), { viewModel.loadBalances() }),
                    subtitle: error
                )
            }
        } else if viewModel.items.isEmpty {
            centered {
                emptyStateBlock
            }
        } else {
            listScroll
        }
    }

    @ViewBuilder
    private var emptyStateBlock: some View {
        VStack(spacing: dimensions.space16) {
            Image(systemName: "person.2")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(colors.textTertiary)
            Text(loc.t(Strings.shared.entities_empty_title))
                .font(typography.sectionTitle)
                .foregroundStyle(colors.textPrimary)
                .multilineTextAlignment(.center)
            if viewModel.canManage {
                NavigationLink(value: EntityRoute.add) {
                    Text(loc.t(Strings.shared.entities_empty_cta))
                        .font(typography.button)
                        .foregroundStyle(colors.onBrand)
                        .padding(.horizontal, dimensions.space24)
                        .frame(height: 44)
                        .background(colors.brand, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(dimensions.space32)
    }

    private var listScroll: some View {
        ScrollView {
            LazyVStack(spacing: dimensions.space12) {
                ForEach(viewModel.items) { item in
                    NavigationLink(value: EntityRoute.detail(id: item.id)) {
                        EntityCard(item: item, currency: currency)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, dimensions.screenPadding)
            .padding(.top, dimensions.space16)
            .padding(.bottom, 96) // clear the FAB
        }
        .refreshable { await pullToRefresh() }
    }

    /// Gate the refresh on connectivity: offline → show the themed offline dialog
    /// (no doomed network call); online → refresh balances.
    @MainActor
    private func pullToRefresh() async {
        guard connectivity.isOnline else {
            offlineDismissed = false
            showOfflineManual = true
            return
        }
        viewModel.loadBalances()
    }

    // MARK: - FAB

    private var fab: some View {
        NavigationLink(value: EntityRoute.add) {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(colors.onBrand)
                .frame(width: 56, height: 56)
                .background(colors.brand, in: Circle())
                .shadow(color: Color.black.opacity(0.25), radius: 8, x: 0, y: 4)
        }
        .buttonStyle(.plain)
        .padding(dimensions.space24)
        .accessibilityLabel(loc.t(Strings.shared.entities_add_cd))
    }

    // MARK: - State block helper

    private func centered<V: View>(@ViewBuilder _ content: () -> V) -> some View {
        VStack {
            Spacer()
            content()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func stateBlock(
        icon: String,
        title: String,
        cta: (String, () -> Void)?,
        subtitle: String? = nil
    ) -> some View {
        VStack(spacing: dimensions.space16) {
            Image(systemName: icon)
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(colors.textTertiary)
            Text(title)
                .font(typography.sectionTitle)
                .foregroundStyle(colors.textPrimary)
                .multilineTextAlignment(.center)
            if let subtitle {
                Text(subtitle)
                    .font(typography.hint)
                    .foregroundStyle(colors.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if let cta {
                Button(action: cta.1) {
                    Text(cta.0)
                        .font(typography.button)
                        .foregroundStyle(colors.onBrand)
                        .padding(.horizontal, dimensions.space24)
                        .frame(height: 44)
                        .background(colors.brand, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(dimensions.space32)
    }

    // MARK: - Helpers

    /// A load/refresh that failed with a network error → surface the offline dialog.
    private var showLoadOffline: Bool {
        if let e = viewModel.balancesError, e is HlError.NetworkUnavailable || e is HlError.GatewayUnreachable {
            return true
        }
        return false
    }

    /// Sum live balances by direction into two decimal-string totals. Uses
    /// NSDecimalNumber for exact decimal arithmetic — money is never a Double.
    private func balanceTotals() -> (receive: String, give: String) {
        var receive = NSDecimalNumber.zero
        var give = NSDecimalNumber.zero
        for item in viewModel.items {
            guard let b = item.balance else { continue }
            let value = NSDecimalNumber(string: b.net)
            if value == NSDecimalNumber.notANumber { continue }
            switch b.direction {
            case .receivable: receive = receive.adding(value)
            case .credit: give = give.adding(value)
            default: break
            }
        }
        return (decimalString(receive), decimalString(give))
    }

    private func decimalString(_ number: NSDecimalNumber) -> String {
        let handler = NSDecimalNumberHandler(
            roundingMode: .plain, scale: 2,
            raiseOnExactness: false, raiseOnOverflow: false,
            raiseOnUnderflow: false, raiseOnDivideByZero: false
        )
        let rounded = number.rounding(accordingToBehavior: handler)
        let fmt = NumberFormatter()
        fmt.numberStyle = .decimal
        fmt.groupingSeparator = ""
        fmt.minimumFractionDigits = 2
        fmt.maximumFractionDigits = 2
        return fmt.string(from: rounded) ?? "0.00"
    }

    private func safeTop() -> CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .safeAreaInsets.top ?? 0
    }
}

// MARK: - Contact card

/// A single white rounded contact card: avatar + name + role glyphs on the left,
/// a signed balance block on the right.
struct EntityCard: View {
    let item: EntityListItem
    let currency: String

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    var body: some View {
        let e = item.entity
        let display = EntityBalanceDisplay.make(item.balance, currency: currency, colors: colors)
        HStack(spacing: dimensions.space12) {
            EntityAvatar(name: e.name, size: 48)

            VStack(alignment: .leading, spacing: 4) {
                Text(e.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(colors.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                EntityRoleGlyphs(roles: e.roles, color: colors.textSecondary)
            }

            Spacer(minLength: dimensions.space8)

            VStack(alignment: .trailing, spacing: 2) {
                Text(display.amount)
                    .font(.system(size: 17, weight: .bold).monospacedDigit())
                    .foregroundStyle(display.color)
                    .lineLimit(1)
                Text(loc.t(display.captionKey))
                    .font(.system(size: 12))
                    .foregroundStyle(colors.textTertiary)
            }
        }
        .padding(dimensions.space16)
        .frame(maxWidth: .infinity)
        .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        .overlay(
            RoundedRectangle(cornerRadius: dimensions.radiusCard)
                .stroke(colors.border, lineWidth: 0.5)
        )
        .shadow(color: Color.black.opacity(0.06), radius: 6, x: 0, y: 2)
    }
}

// MARK: - Bottom-rounded rectangle shape

/// A rectangle with only its bottom two corners rounded — the gradient header.
struct BottomRoundedRectangle: Shape {
    var radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(
            UIBezierPath(
                roundedRect: rect,
                byRoundingCorners: [.bottomLeft, .bottomRight],
                cornerRadii: CGSize(width: radius, height: radius)
            ).cgPath
        )
    }
}
