import SwiftUI
import SharedLogic

/// Entity DETAIL — ticket #37 real UI.
///
/// Presentation-only. A FIXED (non-scrolling) blue hero pinned at the top holds
/// a back button, "CONTACT" eyebrow, name + role badge, an initials monogram
/// (which doubles as the Edit/Archive actions menu, gated by `canManage` and
/// disabled for Walk-in), a translucent BALANCE card, and translucent contact
/// rows. Only the "TRANSACTIONS" coming-soon placeholder scrolls below on the
/// grey background — no fake transaction rows (there is no data source yet).
struct EntityDetailView: View {
    let entity: Entity
    let currency: String
    @ObservedObject var viewModel: EntitiesViewModel
    let session: UserSession
    let config: FirebaseClientConfig
    /// Push the Edit screen onto the flow's NavigationStack (owned by EntitiesView).
    let onEdit: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    @State private var balance: EntityBalance? = nil
    @State private var loadingBalance = false
    @State private var showArchiveConfirm = false
    @State private var showPrint = false

    var body: some View {
        VStack(spacing: 0) {
            fixedHero

            ScrollView {
                transactionsSection
            }
        }
        .background(colors.background.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
        .navigationBarHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task { await refreshBalance() }
        .modifier(ThemedDialog(
            isPresented: $showArchiveConfirm,
            title: loc.t(Strings.shared.entity_detail_archive_title),
            message: loc.t(Strings.shared.entity_detail_archive_body),
            primaryLabel: loc.t(Strings.shared.entity_detail_archive_confirm),
            primaryDestructive: true,
            onPrimary: {
                viewModel.archive(entity)
                dismiss()
            },
            secondaryLabel: loc.t(Strings.shared.entity_detail_cancel)
        ))
        .sheet(isPresented: $showPrint) {
            PrintStatementSheet(entity: entity, session: session, config: config)
        }
    }

    // MARK: - Fixed blue hero (pinned, does not scroll)

    private var fixedHero: some View {
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
                    Text(loc.t(Strings.shared.entity_detail_eyebrow))
                        .font(.system(size: 12, weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(colors.onBrand.opacity(0.7))
                    HStack(spacing: dimensions.space8) {
                        Text(entity.name)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(colors.onBrand)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        EntityRoleGlyphs(roles: entity.roles, color: colors.onBrand)
                    }
                }

                Spacer(minLength: dimensions.space8)

                overflowMenu
            }

            balanceCard
            contactRows
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, topInset + dimensions.space16)
        .padding(.bottom, dimensions.space24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
        // Flat / square bottom edge — no corner rounding.
    }

    private var overflowMenu: some View {
        Menu {
            Button {
                onEdit()
            } label: {
                Label(loc.t(Strings.shared.entity_detail_edit), systemImage: "pencil")
            }
            .disabled(entity.isWalkIn)

            if !entity.isWalkIn {
                Button(role: .destructive) {
                    showArchiveConfirm = true
                } label: {
                    Label(loc.t(Strings.shared.entity_detail_archive), systemImage: "archivebox")
                }
            }
        } label: {
            // Initials monogram doubles as the actions affordance for MANAGE users.
            Text(EntityAvatar.initials(from: entity.name))
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(colors.onBrand)
                .frame(width: 40, height: 40)
                .background(colors.onBrand.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        }
        .disabled(!viewModel.canManage || entity.isWalkIn)
        .accessibilityLabel(loc.t(Strings.shared.entity_detail_menu_cd))
    }

    private var balanceCard: some View {
        let (bigText, pillText, pillColor) = balanceDisplay()
        return HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: dimensions.space8) {
                Text(loc.t(Strings.shared.entity_detail_balance_label))
                    .font(.system(size: 12, weight: .bold))
                    .tracking(1)
                    .foregroundStyle(colors.onBrand.opacity(0.7))
                if loadingBalance {
                    ProgressView().tint(colors.onBrand)
                } else {
                    Text(bigText)
                        .font(.system(size: 30, weight: .bold).monospacedDigit())
                        .foregroundStyle(colors.onBrand)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
            }
            Spacer()
            Text(pillText)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(pillColor)
                .padding(.horizontal, dimensions.space12)
                .padding(.vertical, 6)
                .background(pillColor.opacity(0.18), in: Capsule())
        }
        .padding(dimensions.space16)
        .frame(maxWidth: .infinity)
        .background(colors.onBrand.opacity(0.12), in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
    }

    @ViewBuilder
    private var contactRows: some View {
        let rows = presentRows()
        if !rows.isEmpty {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    if index > 0 {
                        Rectangle()
                            .fill(colors.onBrand.opacity(0.15))
                            .frame(height: 1)
                    }
                    HStack(spacing: dimensions.space12) {
                        Image(systemName: row.icon)
                            .font(.system(size: 14))
                            .foregroundStyle(colors.onBrand.opacity(0.7))
                            .frame(width: 20)
                        Text(row.value)
                            .font(typography.body)
                            .foregroundStyle(colors.onBrand)
                            .lineLimit(row.icon == "note.text" ? 3 : 1)
                            .truncationMode(.tail)
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, dimensions.space12)
                    .padding(.horizontal, dimensions.space16)
                }
            }
            .background(colors.onBrand.opacity(0.08), in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        }
    }

    // MARK: - Transactions (coming-soon placeholder — the only scrolling area)

    private var transactionsSection: some View {
        VStack(alignment: .leading, spacing: dimensions.space24) {
            if entity.isWalkIn {
                Text(loc.t(Strings.shared.entity_form_opening_readonly_note))
                    .font(typography.hint)
                    .foregroundStyle(colors.textSecondary)
                    .padding(dimensions.space16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            }

            EntitySectionHeader(title: loc.t(Strings.shared.entity_detail_transactions), color: colors.brand)

            Button(action: { showPrint = true }) {
                Label(loc.t(Strings.shared.statement_print), systemImage: "printer")
                    .font(typography.body)
            }
            .buttonStyle(.plain)
            .foregroundStyle(colors.brand)

            VStack(spacing: dimensions.space12) {
                Image(systemName: "doc.text")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(colors.textTertiary)
                    .frame(width: 56, height: 56)
                    .background(colors.surfaceAlt, in: Circle())
                Text(loc.t(Strings.shared.entity_detail_transactions_empty))
                    .font(typography.body)
                    .foregroundStyle(colors.textTertiary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, dimensions.space40)
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, dimensions.space24)
        .padding(.bottom, dimensions.space40)
    }

    // MARK: - Balance / row helpers

    private func balanceDisplay() -> (String, String, Color) {
        guard let balance = balance else {
            return (
                MoneyFormat.shared.format(amount: "0.00", currency: currency),
                loc.t(Strings.shared.entity_detail_settled),
                colors.textTertiary
            )
        }
        switch balance.direction {
        case .receivable:
            return (
                MoneyFormat.shared.formatSigned(absoluteAmount: balance.net, currency: currency, positive: true, negative: false),
                loc.t(Strings.shared.entity_detail_they_owe_you),
                colors.success
            )
        case .credit:
            return (
                MoneyFormat.shared.format(amount: balance.net, currency: currency),
                loc.t(Strings.shared.entity_detail_you_owe_them),
                colors.error
            )
        default:
            return (
                MoneyFormat.shared.format(amount: balance.net, currency: currency),
                loc.t(Strings.shared.entity_detail_settled),
                colors.onBrand.opacity(0.85)
            )
        }
    }

    private struct ContactRow { let icon: String; let value: String }

    private func presentRows() -> [ContactRow] {
        var rows: [ContactRow] = []
        if let phone = entity.phones.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            rows.append(ContactRow(icon: "phone.fill", value: phone))
        }
        if let email = entity.email, !email.isEmpty {
            rows.append(ContactRow(icon: "envelope.fill", value: email))
        }
        if let address = entity.address, !address.isEmpty {
            rows.append(ContactRow(icon: "mappin.and.ellipse", value: address))
        }
        if let notes = entity.notes, !notes.isEmpty {
            rows.append(ContactRow(icon: "note.text", value: notes))
        }
        return rows
    }

    private func refreshBalance() async {
        loadingBalance = true
        balance = await viewModel.fetchBalance(id: entity.id)
        loadingBalance = false
    }

    private func safeTop() -> CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .safeAreaInsets.top ?? 0
    }
}
