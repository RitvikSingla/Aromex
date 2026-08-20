import SwiftUI
import SharedLogic

/// iOS Sales History (ticket #84) — bare-but-stable phone screen on the shared #83 query layer.
/// Gates the feature on `sales` VIEW in the UI layer (the VM carries no permission logic). One
/// `.searchable` box drives the instant local filter (as-you-type) and the server smart-search
/// (on submit); tapping a row pushes the detail with the invoice's Open / Share / Download / Retry.
struct SalesHistoryView: View {
    let session: UserSession
    let config: FirebaseClientConfig

    @StateObject private var vm = SalesHistoryViewModel()
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.dismiss) private var dismiss

    /// Detail navigation is a plain @State bool — **not** derived from `vm.detail`. A derived
    /// binding with a side-effecting setter (`closeDetail()`) collapses the whole stack when a
    /// sheet (Share) is presented from inside the pushed detail: SwiftUI fires the nav binding's
    /// setter with `false` mid-transition, niling `vm.detail` and popping to the dashboard.
    @State private var showDetail = false

    var body: some View {
        NavigationStack {
            Group {
                if session.permissions.sales == PermissionLevel.none {
                    Text(loc.t(Strings.shared.sales_history_no_access)).foregroundStyle(.secondary)
                } else {
                    listContent
                }
            }
            .navigationTitle(loc.t(Strings.shared.sales_history_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.sales_close_cd)) { dismiss() }
                }
            }
            .navigationDestination(isPresented: $showDetail) {
                SaleHistoryDetailView(vm: vm).environmentObject(loc)
            }
            .onChange(of: showDetail) { presented in
                if !presented { vm.closeDetail() }
            }
        }
        .onAppear { vm.bind(session: session, config: config) }
    }

    @ViewBuilder private var listContent: some View {
        Group {
            if vm.isLoading {
                ProgressView()
            } else if vm.visibleSales.isEmpty {
                emptyState
            } else {
                List {
                    if let hint = searchHint {
                        Text(hint).font(.footnote).foregroundStyle(.secondary)
                    }
                    ForEach(vm.visibleSales, id: \.saleId) { sale in
                        Button {
                            vm.openSale(saleId: sale.saleId)
                            showDetail = true
                        } label: {
                            SaleRow(sale: sale, customerName: vm.customerName(for: sale), currency: vm.currency, timezone: vm.timezone)
                        }
                        .buttonStyle(.plain)
                        .onAppear { vm.loadMoreIfNeeded(current: sale) }
                    }
                    if vm.isLoadingMore {
                        HStack {
                            Spacer()
                            ProgressView()
                            Text(loc.t(Strings.shared.sales_history_loading_more)).font(.footnote).foregroundStyle(.secondary)
                            Spacer()
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .searchable(text: $vm.searchText, prompt: loc.t(Strings.shared.sales_history_search_placeholder))
        .onSubmit(of: .search) { vm.onSearchSubmit() }
        .onChange(of: vm.searchText) { newValue in
            if newValue.isEmpty { vm.clearSearch() }
        }
    }

    /// The "Searched by …" interpretation hint (ticket #84) — only after a submitted server search.
    private var searchHint: String? {
        switch vm.searchKind {
        case .imei: return loc.t(Strings.shared.sales_history_searched_by_imei)
        case .invoice: return loc.t(Strings.shared.sales_history_searched_by_invoice)
        case .customer: return loc.t(Strings.shared.sales_history_searched_by_customer)
        case .none: return nil
        }
    }

    private var emptyState: some View {
        VStack(spacing: 6) {
            Text(loc.t(vm.hasActiveSearch ? Strings.shared.sales_history_no_match_title : Strings.shared.sales_history_empty_title))
                .font(.headline)
            Text(loc.t(vm.hasActiveSearch ? Strings.shared.sales_history_no_match_body : Strings.shared.sales_history_empty_body))
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding()
    }
}

// MARK: - List row

private struct SaleRow: View {
    let sale: SaleSummary
    let customerName: String
    let currency: String
    let timezone: String
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 8) {
                Text(customerName).font(.body).lineLimit(1).truncationMode(.tail)
                if sale.status.isVoided {
                    Text(loc.t(Strings.shared.sales_history_voided_badge))
                        .font(.caption2).fontWeight(.semibold)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.red.opacity(0.12))
                        .foregroundStyle(.red)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                }
                Spacer()
                Text(MoneyFormat.shared.format(amount: sale.grandTotal, currency: currency)).fontWeight(.semibold)
            }
            Text(itemSummary).font(.subheadline).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
            HStack {
                Text("\(SalesHistoryFormat.dateTime(sale.createdAtMillis, timezone))  ·  \(sale.invoiceNumber ?? loc.t(Strings.shared.sales_history_no_invoice))")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                Spacer()
                if !Money.shared.isZero(value: sale.balanceRemaining) {
                    Text(MoneyFormat.shared.format(amount: sale.balanceRemaining, currency: currency))
                        .font(.caption).fontWeight(.medium)
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(Color.red.opacity(0.15))
                        .foregroundStyle(.red)
                        .clipShape(Capsule())
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private var itemSummary: String {
        let first = sale.firstItemLabel.isEmpty ? "—" : sale.firstItemLabel
        if sale.itemCount > 1 {
            return "\(first)  \(loc.t(Strings.shared.sales_history_items_more, "\(sale.itemCount - 1)"))"
        }
        return first
    }
}

// MARK: - Detail

private struct SaleHistoryDetailView: View {
    @ObservedObject var vm: SalesHistoryViewModel
    @EnvironmentObject private var loc: LocalizationManager

    @State private var isPreparing = false
    @State private var shareFile: ShareFile? = nil
    @State private var exportDocument: PdfFileDocument? = nil
    @State private var showExporter = false
    @State private var pdfMessage: String? = nil
    @State private var showVoidSheet = false

    var body: some View {
        Group {
            if let detail = vm.detail {
                Form {
                    if detail.isVoided { voidedBanner(detail) }
                    headerSection(detail)
                    linesSection(detail)
                    totalsSection(detail)
                    if let note = detail.note, !note.isEmpty {
                        Section(loc.t(Strings.shared.sales_history_detail_note)) { Text(note) }
                    }
                    Section(loc.t(Strings.shared.sales_invoice_label)) {
                        invoiceActions
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(loc.t(Strings.shared.sales_history_detail_title))
        .navigationBarTitleDisplayMode(.inline)
        // Void — admin-only, hidden once voided; a spinner while the reversal runs.
        .toolbar {
            if vm.canVoidOpenSale {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if vm.isVoiding {
                        ProgressView()
                    } else {
                        Button(role: .destructive) { vm.clearVoidError(); showVoidSheet = true } label: {
                            Image(systemName: "nosign")
                        }
                        .tint(.red)
                    }
                }
            }
        }
        .sheet(isPresented: $showVoidSheet) {
            VoidSaleSheet(vm: vm, isPresented: $showVoidSheet).environmentObject(loc)
        }
        // Close the sheet the moment the sale flips VOIDED (a successful reversal).
        .onChange(of: vm.detail?.isVoided) { voided in
            if voided == true { showVoidSheet = false }
        }
        // The PDF presentations are hosted here at the detail root — a single stable host — rather
        // than inside a Form row: nesting .sheet/.fileExporter/.alert in a row of a Form that
        // itself sits in a navigationDestination is fragile and can dismiss the wrong layer.
        .sheet(item: $shareFile) { file in ActivityView(items: [file.url]) }
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .pdf,
            defaultFilename: InvoicePdf.fileName(vm.detailInvoice.number)
        ) { result in
            pdfMessage = loc.t((try? result.get()) != nil
                ? Strings.shared.sales_history_pdf_saved
                : Strings.shared.sales_history_pdf_error)
        }
        .alert(pdfMessage ?? "", isPresented: Binding(get: { pdfMessage != nil }, set: { if !$0 { pdfMessage = nil } })) {
            Button(loc.t(Strings.shared.sales_close_cd)) { pdfMessage = nil }
        }
    }

    // MARK: - Invoice actions (Open / Share the file / Save to Files / Retry)

    @ViewBuilder private var invoiceActions: some View {
        switch vm.detailInvoice.status {
        case .issued:
            Text(vm.detailInvoice.number ?? "—").fontWeight(.semibold).lineLimit(1).truncationMode(.tail)
            if let raw = vm.detailInvoice.url, let url = URL(string: raw) {
                Link(loc.t(Strings.shared.sales_invoice_open), destination: url)
                Button(loc.t(Strings.shared.sales_history_share)) { prepareShare(raw) }.disabled(isPreparing)
                Button(loc.t(Strings.shared.sales_history_download)) { prepareDownload(raw) }.disabled(isPreparing)
                if isPreparing {
                    HStack(spacing: 8) { ProgressView(); Text(loc.t(Strings.shared.sales_invoice_preparing)).font(.footnote).foregroundStyle(.secondary) }
                }
            }
        case .failed:
            Text(loc.t(Strings.shared.sales_invoice_failed)).foregroundStyle(.secondary)
            if vm.isRetryingInvoice {
                HStack(spacing: 8) { ProgressView(); Text(loc.t(Strings.shared.sales_invoice_retrying)).font(.footnote).foregroundStyle(.secondary) }
            } else {
                if vm.invoiceRetryError {
                    Text(loc.t(Strings.shared.sales_invoice_retry_error)).font(.footnote).foregroundStyle(.red)
                }
                Button(loc.t(Strings.shared.sales_invoice_retry)) { vm.retryInvoice() }.disabled(!vm.canRetryInvoice)
            }
        default: // pending / absent
            HStack(spacing: 8) { ProgressView(); Text(loc.t(Strings.shared.sales_invoice_preparing)).foregroundStyle(.secondary) }
        }
    }

    /// Download the actual PDF, write it to a temp file, and present the system share sheet on it.
    private func prepareShare(_ url: String) {
        isPreparing = true
        Task {
            let data = await InvoicePdf.download(url)
            defer { isPreparing = false }
            guard let data = data,
                  let file = InvoicePdf.writeTemp(data, name: InvoicePdf.fileName(vm.detailInvoice.number)) else {
                pdfMessage = loc.t(Strings.shared.sales_history_pdf_error)
                return
            }
            shareFile = ShareFile(url: file)
        }
    }

    /// Download the actual PDF bytes, then present "Save to Files" (the iOS download equivalent).
    private func prepareDownload(_ url: String) {
        isPreparing = true
        Task {
            let data = await InvoicePdf.download(url)
            defer { isPreparing = false }
            guard let data = data else {
                pdfMessage = loc.t(Strings.shared.sales_history_pdf_error)
                return
            }
            exportDocument = PdfFileDocument(data: data)
            showExporter = true
        }
    }

    /// A read-only banner on a voided sale (ticket #85), naming the reason if stored.
    private func voidedBanner(_ detail: SaleDetail) -> some View {
        Section {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "nosign").foregroundStyle(.red)
                VStack(alignment: .leading, spacing: 2) {
                    Text(loc.t(Strings.shared.sales_history_voided_note)).font(.subheadline).foregroundStyle(.red)
                    if let reason = detail.voidState.reason, !reason.isEmpty {
                        Text(reason).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func headerSection(_ detail: SaleDetail) -> some View {
        Section {
            fact(loc.t(Strings.shared.sales_history_detail_customer), vm.customerName(for: detail))
            if detail.isWalkIn {
                if let name = detail.buyerName, !name.isEmpty { fact(loc.t(Strings.shared.sales_history_detail_buyer), name) }
                if let phone = detail.buyerPhone, !phone.isEmpty { fact(loc.t(Strings.shared.sales_history_detail_buyer_phone), phone) }
            }
            if let seller = vm.sellerName { fact(loc.t(Strings.shared.sales_history_detail_sold_by), seller) }
            fact(loc.t(Strings.shared.sales_history_detail_sold_at), SalesHistoryFormat.dateTime(detail.createdAtMillis, vm.timezone))
        }
    }

    private func linesSection(_ detail: SaleDetail) -> some View {
        Section(loc.t(Strings.shared.sales_history_detail_items)) {
            ForEach(Array(detail.lines.enumerated()), id: \.offset) { _, line in
                lineRow(line, currency: vm.currency)
            }
        }
    }

    @ViewBuilder private func lineRow(_ line: SaleRecordLine, currency: String) -> some View {
        let label = (line as? SaleRecordLineInventory)?.label ?? (line as? SaleRecordLineCustom)?.name ?? "—"
        let imei = (line as? SaleRecordLineInventory)?.imei
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).lineLimit(2)
                if let imei = imei, !imei.isEmpty {
                    Text(imei).font(.caption).foregroundStyle(.secondary).monospaced()
                }
                if !Money.shared.isZero(value: line.lineDiscount) {
                    Text("\(loc.t(Strings.shared.sales_history_detail_line_discount)): \(MoneyFormat.shared.format(amount: line.lineDiscount, currency: currency))")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(MoneyFormat.shared.format(amount: line.netPrice, currency: currency))
        }
    }

    private func totalsSection(_ detail: SaleDetail) -> some View {
        Section {
            totals(loc.t(Strings.shared.sales_totals_subtotal), MoneyFormat.shared.format(amount: detail.subtotal, currency: vm.currency))
            if !Money.shared.isZero(value: detail.saleDiscount) {
                totals(loc.t(Strings.shared.sales_history_detail_sale_discount), "-" + MoneyFormat.shared.format(amount: detail.saleDiscount, currency: vm.currency))
            }
            ForEach(detail.taxLines, id: \.name) { tax in
                totals("\(tax.name) (\(tax.rate))", MoneyFormat.shared.format(amount: tax.amount, currency: vm.currency))
            }
            totals(loc.t(Strings.shared.sales_totals_grand_total), MoneyFormat.shared.format(amount: detail.grandTotal, currency: vm.currency), emphasize: true)
            totals(loc.t(Strings.shared.sales_totals_paid), MoneyFormat.shared.format(amount: detail.amountPaid, currency: vm.currency))
            totals(loc.t(Strings.shared.sales_totals_balance), MoneyFormat.shared.format(amount: detail.balanceRemaining, currency: vm.currency), emphasize: true)
        }
    }

    private func fact(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value)
        }
    }

    private func totals(_ label: String, _ value: String, emphasize: Bool = false) -> some View {
        HStack {
            Text(label).fontWeight(emphasize ? .semibold : .regular)
            Spacer()
            Text(value).fontWeight(emphasize ? .bold : .regular)
        }
    }
}

// MARK: - Void confirmation (ticket #85)

/// The Void confirmation sheet: states what a void reverses, **requires a typed reason** (Confirm
/// disabled until non-blank), can't be swipe-dismissed mid-void, and uses a destructive Confirm that
/// shows a spinner while the reversal runs. Admin-only; the CF re-verifies admin server-side.
private struct VoidSaleSheet: View {
    @ObservedObject var vm: SalesHistoryViewModel
    @Binding var isPresented: Bool
    @EnvironmentObject private var loc: LocalizationManager
    @State private var reason = ""

    private var trimmedReason: String { reason.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(loc.t(Strings.shared.sales_history_void_dialog_body))
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Section(loc.t(Strings.shared.sales_history_void_reason_label)) {
                    TextField(loc.t(Strings.shared.sales_history_void_reason_ph), text: $reason, axis: .vertical)
                        .disabled(vm.isVoiding)
                }
                if let err = vm.voidError {
                    Section { Text(err).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle(loc.t(Strings.shared.sales_history_void_dialog_title))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(vm.isVoiding)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.sales_history_void_cancel)) {
                        vm.clearVoidError(); isPresented = false
                    }.disabled(vm.isVoiding)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if vm.isVoiding {
                        ProgressView()
                    } else {
                        Button(loc.t(Strings.shared.sales_history_void_confirm), role: .destructive) {
                            vm.voidSale(reason: trimmedReason)
                        }.disabled(trimmedReason.isEmpty)
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Formatting

enum SalesHistoryFormat {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "MMM d, yyyy · h:mm a"
        return f
    }()

    /// Formats an epoch-millis instant in the shop's timezone; falls back to UTC on a bad zone id.
    static func dateTime(_ millis: Int64, _ timezone: String) -> String {
        if millis <= 0 { return "—" }
        let f = formatter
        f.timeZone = TimeZone(identifier: timezone) ?? TimeZone(identifier: "UTC")
        return f.string(from: Date(timeIntervalSince1970: Double(millis) / 1000.0))
    }
}
