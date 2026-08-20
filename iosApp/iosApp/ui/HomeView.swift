import SwiftUI
import SharedLogic

struct HomeView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @ObservedObject var viewModel: HomeViewModel

    /// Entities is its own nav graph (its own NavigationStack), reached from Home
    /// as a full-screen cover — NOT pushed onto Home's stack, so there is exactly
    /// one NavigationStack in the entities flow (no nested-stack AttributeGraph cycles).
    @State private var showEntities = false
    @State private var showInventory = false
    @State private var showSales = false
    @State private var showSalesHistory = false
    @State private var showCommissionRules = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header
                    balancesPanel
                    entitiesLink
                    inventoryLink
                    salesLink
                    salesHistoryLink
                    commissionRulesLink
                    signOutButton
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle(loc.t(Strings.shared.home_title))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(loc.t(Strings.shared.home_welcome))
                .font(.title2.weight(.semibold))
            if let session = viewModel.session {
                Text(loc.t(Strings.shared.home_signed_in_as, session.email))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text(loc.t(Strings.shared.home_role, roleLabel(session.role)))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    // MARK: - Balances panel

    private var balancesPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(loc.t(Strings.shared.home_balances_title))
                .font(.headline)

            if viewModel.isLoadingBalances && viewModel.accounts.isEmpty {
                loadingRow
            } else if let error = viewModel.balancesError {
                errorRow(error)
            } else if viewModel.accounts.isEmpty {
                emptyRow
            } else {
                balancesList
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var loadingRow: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(loc.t(Strings.shared.home_balances_loading))
                .foregroundStyle(.secondary)
        }
    }

    private var emptyRow: some View {
        Text(loc.t(Strings.shared.home_balances_empty))
            .foregroundStyle(.secondary)
    }

    private func errorRow(_ error: HlError) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(loc.t(Strings.shared.home_balances_error))
                .font(.subheadline.weight(.semibold))
            Text(errorMessage(error))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button {
                viewModel.retryBalances()
            } label: {
                Text(loc.t(Strings.shared.home_balances_retry))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
        }
    }

    private var balancesList: some View {
        let currency = viewModel.session?.currency ?? ""
        let grouped = groupedAccounts(viewModel.accounts)
        return VStack(alignment: .leading, spacing: 16) {
            ForEach(grouped, id: \.title) { group in
                VStack(alignment: .leading, spacing: 6) {
                    Text(group.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ForEach(group.accounts, id: \.id) { account in
                        accountRow(account, currency: currency)
                    }
                }
            }
        }
    }

    private func accountRow(_ account: LedgerAccount, currency: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(account.name)
                    .font(.body)
                Text(accountTypeLabel(account.type))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text("\(account.balance) \(currency)")
                .font(.body.monospacedDigit())
        }
        .padding(.vertical, 6)
    }

    // MARK: - Entities (bare test-only entry point)

    @ViewBuilder
    private var entitiesLink: some View {
        if let session = viewModel.session, let config = viewModel.boundConfig {
            Button {
                showEntities = true
            } label: {
                HStack {
                    Text("Profiles / Entities")
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
            .fullScreenCover(isPresented: $showEntities) {
                EntitiesView(session: session, config: config)
            }
        }
    }

    // MARK: - Inventory (bare test-only entry point)

    @ViewBuilder
    private var inventoryLink: some View {
        if let session = viewModel.session, let config = viewModel.boundConfig {
            Button {
                showInventory = true
            } label: {
                HStack {
                    Text("Inventory")
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
            .fullScreenCover(isPresented: $showInventory) {
                InventoryView(session: session, config: config)
            }
        }
    }

    // MARK: - Commission rules (ticket #97 — admin only)

    @ViewBuilder
    private var commissionRulesLink: some View {
        if let session = viewModel.session, let config = viewModel.boundConfig, session.role == UserRole.admin {
            Button {
                showCommissionRules = true
            } label: {
                HStack {
                    Text(loc.t(Strings.shared.commission_rules_title))
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
            .fullScreenCover(isPresented: $showCommissionRules) {
                CommissionRulesView(session: session, config: config)
            }
        }
    }

    // MARK: - Sales (bare test-only entry point)

    @ViewBuilder
    private var salesLink: some View {
        if let session = viewModel.session, let config = viewModel.boundConfig {
            Button {
                showSales = true
            } label: {
                HStack {
                    Text("Sales")
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
            .fullScreenCover(isPresented: $showSales) {
                SalesView(session: session, config: config)
            }
        }
    }

    // MARK: - Sales History (ticket #84)

    @ViewBuilder
    private var salesHistoryLink: some View {
        if let session = viewModel.session, let config = viewModel.boundConfig {
            Button {
                showSalesHistory = true
            } label: {
                HStack {
                    Text(loc.t(Strings.shared.sales_history_title))
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
            .fullScreenCover(isPresented: $showSalesHistory) {
                SalesHistoryView(session: session, config: config)
            }
        }
    }

    // MARK: - Sign out

    private var signOutButton: some View {
        Button(role: .destructive) {
            viewModel.signOut()
        } label: {
            HStack {
                if viewModel.isSigningOut {
                    ProgressView()
                        .progressViewStyle(.circular)
                }
                Text(loc.t(Strings.shared.home_sign_out))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .disabled(viewModel.isSigningOut)
    }

    // MARK: - Grouping + labels

    private struct BalanceGroup { let title: String; let accounts: [LedgerAccount] }

    private func groupedAccounts(_ accounts: [LedgerAccount]) -> [BalanceGroup] {
        var cash: [LedgerAccount] = []
        var bank: [LedgerAccount] = []
        var creditCard: [LedgerAccount] = []
        var other: [LedgerAccount] = []
        for account in accounts {
            switch account.type {
            case AccountType.cash: cash.append(account)
            case AccountType.bank: bank.append(account)
            case AccountType.creditCard: creditCard.append(account)
            default: other.append(account)
            }
        }
        var groups: [BalanceGroup] = []
        if !cash.isEmpty {
            groups.append(BalanceGroup(title: loc.t(Strings.shared.home_balances_section_cash), accounts: cash))
        }
        if !bank.isEmpty {
            groups.append(BalanceGroup(title: loc.t(Strings.shared.home_balances_section_bank), accounts: bank))
        }
        if !creditCard.isEmpty {
            groups.append(BalanceGroup(title: loc.t(Strings.shared.home_balances_section_credit_card), accounts: creditCard))
        }
        if !other.isEmpty {
            groups.append(BalanceGroup(title: loc.t(Strings.shared.home_balances_section_other), accounts: other))
        }
        return groups
    }

    private func accountTypeLabel(_ type: AccountType) -> String {
        switch type {
        case AccountType.cash:        return loc.t(Strings.shared.account_type_cash)
        case AccountType.bank:        return loc.t(Strings.shared.account_type_bank)
        case AccountType.creditCard:  return loc.t(Strings.shared.account_type_credit_card)
        case AccountType.receivable:  return loc.t(Strings.shared.account_type_receivable)
        case AccountType.payable:     return loc.t(Strings.shared.account_type_payable)
        case AccountType.revenue:     return loc.t(Strings.shared.account_type_revenue)
        case AccountType.expense:     return loc.t(Strings.shared.account_type_expense)
        case AccountType.tax:         return loc.t(Strings.shared.account_type_tax)
        default:                      return loc.t(Strings.shared.account_type_other)
        }
    }

    private func roleLabel(_ role: UserRole) -> String {
        // Role is displayed inside the `home_role` template. The English
        // template renders "Role: {0}"; we pass through the raw role name.
        // A dedicated dictionary entry for role names can be added later.
        if role == UserRole.admin { return "Admin" }
        return "Member"
    }

    private func errorMessage(_ error: HlError) -> String {
        if error is HlError.NetworkUnavailable {
            return loc.t(Strings.shared.hl_error_network)
        } else if error is HlError.GatewayUnreachable {
            return loc.t(Strings.shared.hl_error_gateway)
        } else if error is HlError.TokenRejected {
            return loc.t(Strings.shared.hl_error_token_rejected)
        } else if error is HlError.HlUnreachable {
            return loc.t(Strings.shared.hl_error_hl_unreachable)
        } else if error is HlError.Unauthorized {
            return loc.t(Strings.shared.hl_error_unauthorized)
        } else {
            return loc.t(Strings.shared.hl_error_unexpected)
        }
    }
}
