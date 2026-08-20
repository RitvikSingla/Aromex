import SwiftUI
import SharedLogic

/// iOS Sales screen (ticket #64) — bare/stock SwiftUI `Form` bound to `SalesViewModel`
/// (ticket #62). Rendering + action dispatch only; the permission gate on `sales` VIEW is
/// checked here in the UI layer since the ViewModel carries none (T2 consumed as-is).
struct SalesView: View {
    let session: UserSession
    let config: FirebaseClientConfig

    @StateObject private var vm = SalesViewModel()
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.dismiss) private var dismiss

    @State private var showPicker = false
    @State private var showAddCustom = false
    @State private var showCustomerPicker = false

    private var locked: Bool {
        if case .submitting = vm.confirmState { return true } else { return false }
    }

    var body: some View {
        NavigationStack {
            Group {
                if session.permissions.sales == PermissionLevel.none {
                    Text(loc.t(Strings.shared.sales_no_access)).foregroundStyle(.secondary)
                } else {
                    form
                }
            }
            .navigationTitle(loc.t(Strings.shared.entities_sidebar_sales))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.sales_close_cd)) { dismiss() }
                }
            }
        }
        .onAppear { vm.bind(session: session, config: config) }
        .sheet(isPresented: $showPicker) { itemPickerSheet }
        .sheet(isPresented: $showAddCustom) {
            AddCustomLineView(currency: vm.currency) { name, price in
                vm.addCustomLine(name: name, price: price)
                showAddCustom = false
            } onCancel: { showAddCustom = false }
        }
        .sheet(isPresented: $showCustomerPicker) { customerPickerSheet }
        .alert(loc.t(Strings.shared.sales_already_sold_title), isPresented: alreadySoldBinding) {
            Button(loc.t(Strings.shared.sales_already_sold_dismiss)) { vm.dismissConfirmState() }
        } message: {
            Text(alreadySoldMessage)
        }
        .alert(loc.t(Strings.shared.sales_error_title), isPresented: errorBinding) {
            Button(loc.t(Strings.shared.sales_error_dismiss)) { vm.dismissConfirmState() }
        } message: {
            Text(errorMessage)
        }
        // A sheet (not an alert) so the invoice row can resolve live with Open/Share/Retry as the
        // CF issues the PDF — an alert can't host the updating row + share sheet.
        .sheet(isPresented: successBinding) {
            SaleCompleteView(vm: vm).environmentObject(loc)
        }
    }

    // MARK: - Form

    private var form: some View {
        Form {
            cartSection
            customerSection
            if vm.isWalkIn { buyerSection }
            if vm.selectedCustomer != nil { customerTaxSection }
            // Named customer only — a walk-in captures its phone in buyerSection above.
            if vm.selectedCustomer != nil && !vm.isWalkIn { customerPhoneSection }
            paymentSection
            noteSection
            taxInclusiveSection
            totalsSection
            confirmSection
        }
    }

    /// Customer tax number at checkout (ticket #106 follow-up): prefilled from the selected customer,
    /// editable for this invoice, with an optional "Save to contact" (named customer + profiles:manage).
    /// A walk-in gets the field but no save.
    private var customerTaxSection: some View {
        Section(loc.t(Strings.shared.sales_checkout_buyer_tax_number_label)) {
            TextField(
                loc.t(Strings.shared.sales_checkout_buyer_tax_number_placeholder),
                text: Binding(get: { vm.buyerTaxNumber }, set: { vm.setBuyerTaxNumber($0) })
            )
            .disabled(locked)
            if vm.savingTaxNumber {
                Text(loc.t(Strings.shared.sales_action_save_tax_to_contact)).font(.footnote).foregroundStyle(.secondary)
            } else if vm.taxNumberSaveError {
                Text(loc.t(Strings.shared.sales_tax_save_error)).font(.footnote).foregroundStyle(.red)
            } else if vm.taxNumberSaved {
                Text(loc.t(Strings.shared.sales_tax_saved_to_contact)).font(.footnote).foregroundStyle(.green)
            } else if vm.canSaveTaxToContact {
                Button(loc.t(Strings.shared.sales_action_save_tax_to_contact)) { vm.saveBuyerTaxNumberToContact() }
                    .disabled(locked)
            }
        }
    }

    /// Customer phone at checkout — the twin of `customerTaxSection`. Prefilled from the named
    /// customer's primary number, editable for this invoice, with an optional "Save to contact"
    /// (profiles:manage). Shown for a named customer only; a walk-in uses `buyerSection` instead.
    private var customerPhoneSection: some View {
        Section(loc.t(Strings.shared.sales_buyer_phone_label)) {
            TextField(
                loc.t(Strings.shared.sales_buyer_phone_placeholder),
                text: Binding(get: { vm.buyerContactPhone }, set: { vm.setBuyerContactPhone($0) })
            )
            .keyboardType(.phonePad)
            .disabled(locked)
            if vm.savingPhone {
                Text(loc.t(Strings.shared.sales_action_save_tax_to_contact)).font(.footnote).foregroundStyle(.secondary)
            } else if vm.phoneSaveError {
                Text(loc.t(Strings.shared.sales_tax_save_error)).font(.footnote).foregroundStyle(.red)
            } else if vm.phoneSaved {
                Text(loc.t(Strings.shared.sales_tax_saved_to_contact)).font(.footnote).foregroundStyle(.green)
            } else if vm.canSavePhoneToContact {
                Button(loc.t(Strings.shared.sales_action_save_tax_to_contact)) { vm.saveBuyerPhoneToContact() }
                    .disabled(locked)
            }
        }
    }

    /// Tax-inclusive pricing toggle (ticket #106): per sale, resets to off on each new sale (the VM
    /// handles the reset). When on, typed prices already contain tax and the totals back it out.
    private var taxInclusiveSection: some View {
        Section {
            Toggle(
                loc.t(Strings.shared.sales_checkout_tax_inclusive_label),
                isOn: Binding(get: { vm.taxInclusive }, set: { vm.setTaxInclusive($0) })
            )
            .disabled(locked)
        }
    }

    /// Walk-in buyer capture (ticket #77): optional Bill-To name/phone for the invoice, shown
    /// only for the anonymous party. Free-text; never blocks Confirm; blank → "Walk-in Customer".
    private var buyerSection: some View {
        Section(loc.t(Strings.shared.sales_buyer_name_label)) {
            TextField(loc.t(Strings.shared.sales_buyer_name_placeholder), text: $vm.buyerName)
                .disabled(locked)
            // Route through setBuyerPhone so the digits-only / max-10 rule holds against paste too.
            TextField(
                loc.t(Strings.shared.sales_buyer_phone_placeholder),
                text: Binding(get: { vm.buyerPhone }, set: { vm.setBuyerPhone($0) })
            )
            .keyboardType(.phonePad)
            .disabled(locked)
        }
    }

    private var cartSection: some View {
        Section(loc.t(Strings.shared.sales_cart_title)) {
            if vm.cartLines.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(loc.t(Strings.shared.sales_cart_empty_title))
                    Text(loc.t(Strings.shared.sales_cart_empty_body))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                ForEach(vm.cartLines) { line in
                    CartLineRow(
                        line: line,
                        currency: vm.currency,
                        hasError: vm.errors.lineDiscountExceedsPrice.contains(line.id),
                        enabled: !locked,
                        priceLabel: loc.t(Strings.shared.sales_cart_col_price),
                        discountLabel: loc.t(Strings.shared.sales_cart_col_discount),
                        netLabel: loc.t(Strings.shared.sales_cart_col_net),
                        errorText: loc.t(Strings.shared.sales_cart_line_discount_error),
                        doneLabel: loc.t(Strings.shared.sales_picker_done),
                        onPriceChange: { vm.setUnitPrice(lineId: line.id, price: $0) },
                        onDiscountChange: { vm.setLineDiscount(lineId: line.id, discount: $0) }
                    )
                }
                .onDelete { offsets in
                    for index in offsets { vm.removeLine(lineId: vm.cartLines[index].id) }
                }
            }

            HStack {
                Button(loc.t(Strings.shared.sales_cart_add_item)) { showAddCustom = true }
                Spacer()
                Button(loc.t(Strings.shared.sales_cart_add_phone)) { showPicker = true }
                    .buttonStyle(.borderedProminent)
            }
            .disabled(locked)

            HStack {
                Text(loc.t(Strings.shared.sales_cart_sale_discount_label))
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                MoneyField(
                    value: vm.saleDiscount,
                    doneLabel: loc.t(Strings.shared.sales_picker_done),
                    onChange: vm.setSaleDiscount
                )
                .disabled(locked)
            }
            if vm.errors.saleDiscountExceedsSubtotal {
                Text(loc.t(Strings.shared.sales_error_sale_discount)).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private var customerSection: some View {
        Section(loc.t(Strings.shared.sales_checkout_customer_label)) {
            Button {
                showCustomerPicker = true
            } label: {
                HStack {
                    Text(vm.selectedCustomer?.name ?? loc.t(Strings.shared.sales_checkout_customer_placeholder))
                        .foregroundStyle(vm.selectedCustomer == nil ? .secondary : .primary)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
            }
            .disabled(locked)

            Button(loc.t(Strings.shared.sales_checkout_walk_in_button)) { vm.selectWalkIn() }
                .disabled(locked)

            if vm.errors.noCustomer {
                Text(loc.t(Strings.shared.sales_error_no_customer)).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private var paymentSection: some View {
        Section(loc.t(Strings.shared.sales_checkout_title)) {
            let symbol = MoneyFormat.shared.symbolOf(currency: vm.currency)
            HStack {
                Text(loc.t(Strings.shared.sales_checkout_cash, symbol)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                MoneyField(value: vm.cashPaid, doneLabel: loc.t(Strings.shared.sales_picker_done), onChange: vm.setCash)
            }
            HStack {
                Text(loc.t(Strings.shared.sales_checkout_card, symbol)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                MoneyField(value: vm.cardPaid, doneLabel: loc.t(Strings.shared.sales_picker_done), onChange: vm.setCard)
            }
            HStack {
                Text(loc.t(Strings.shared.sales_checkout_bank, symbol)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                MoneyField(value: vm.bankPaid, doneLabel: loc.t(Strings.shared.sales_picker_done), onChange: vm.setBank)
            }
            .disabled(locked)
            if vm.errors.overpayment {
                Text(loc.t(Strings.shared.sales_error_overpayment)).font(.footnote).foregroundStyle(.red)
            }
            if vm.errors.walkInMustPayInFull {
                Text(loc.t(Strings.shared.sales_error_walk_in_full)).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private var noteSection: some View {
        Section(loc.t(Strings.shared.sales_checkout_note_label)) {
            TextField(loc.t(Strings.shared.sales_checkout_note_placeholder), text: $vm.note, axis: .vertical)
                .disabled(locked)
        }
    }

    private var totalsSection: some View {
        let currency = vm.currency
        let totals = vm.totals
        return Section {
            TotalsRow(label: loc.t(Strings.shared.sales_totals_subtotal), value: MoneyFormat.shared.format(amount: totals.subtotal, currency: currency))
            ForEach(totals.taxLines, id: \.name) { tax in
                TotalsRow(label: "\(tax.name) (\(tax.rate))", value: MoneyFormat.shared.format(amount: tax.amount, currency: currency))
            }
            TotalsRow(label: loc.t(Strings.shared.sales_totals_grand_total), value: MoneyFormat.shared.format(amount: totals.grandTotal, currency: currency), emphasize: true)
            TotalsRow(label: loc.t(Strings.shared.sales_totals_paid), value: MoneyFormat.shared.format(amount: vm.amountPaid, currency: currency))
            TotalsRow(label: loc.t(Strings.shared.sales_totals_balance), value: MoneyFormat.shared.format(amount: vm.balanceRemaining, currency: currency), emphasize: true)
        }
    }

    private var confirmSection: some View {
        Section {
            if vm.errors.emptyCart {
                Text(loc.t(Strings.shared.sales_error_empty_cart)).font(.footnote).foregroundStyle(.red)
            }
            Button {
                vm.confirmSale()
            } label: {
                HStack {
                    Spacer()
                    if locked {
                        ProgressView().padding(.trailing, 4)
                        Text(loc.t(Strings.shared.sales_confirm_submitting))
                    } else {
                        Text(loc.t(Strings.shared.sales_confirm_button))
                    }
                    Spacer()
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(!vm.canConfirm)
        }
    }

    // MARK: - Item picker sheet

    private var itemPickerSheet: some View {
        NavigationStack {
            Group {
                if vm.visibleUnits.isEmpty {
                    Text(loc.t(Strings.shared.sales_picker_empty)).foregroundStyle(.secondary)
                } else {
                    List(vm.visibleUnits, id: \.serialId) { serial in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(vm.label(for: serial)).lineLimit(1).truncationMode(.tail)
                                Text(serial.imei).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                            }
                            Spacer()
                            Button(loc.t(Strings.shared.sales_picker_add_cd)) {
                                vm.addUnitToCart(serialId: serial.serialId)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
            .searchable(text: $vm.pickerSearchQuery, prompt: loc.t(Strings.shared.sales_picker_search))
            .navigationTitle(loc.t(Strings.shared.sales_picker_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc.t(Strings.shared.sales_picker_done)) { showPicker = false }
                }
            }
        }
    }

    // MARK: - Customer picker sheet

    private var customerPickerSheet: some View {
        NavigationStack {
            Group {
                if vm.customerOptions.isEmpty {
                    Text(loc.t(Strings.shared.inventory_dropdown_no_results)).foregroundStyle(.secondary)
                } else {
                    List(vm.customerOptions, id: \.id) { customer in
                        Button {
                            vm.selectCustomer(customer)
                            showCustomerPicker = false
                        } label: {
                            Text(customer.name)
                        }
                    }
                }
            }
            .searchable(text: $vm.customerSearchQuery)
            .navigationTitle(loc.t(Strings.shared.sales_checkout_customer_label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.sales_close_cd)) { showCustomerPicker = false }
                }
            }
        }
    }

    // MARK: - Confirm-outcome bindings

    // `set` is a no-op: SwiftUI calls it (with `false`) right after any alert button's own
    // action already runs, so wiring it to `dismissConfirmState()` too would double-fire it.
    private var alreadySoldBinding: Binding<Bool> {
        Binding(
            get: { if case .alreadySold = vm.confirmState { return true } else { return false } },
            set: { _ in }
        )
    }

    private var alreadySoldMessage: String {
        if case let .alreadySold(_, label) = vm.confirmState {
            return loc.t(Strings.shared.sales_already_sold_body, label)
        }
        return ""
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { if case .error = vm.confirmState { return true } else { return false } },
            set: { _ in }
        )
    }

    private var errorMessage: String {
        if case let .error(message) = vm.confirmState { return message }
        return ""
    }

    private var successBinding: Binding<Bool> {
        Binding(
            get: { if case .success = vm.confirmState { return true } else { return false } },
            // A pull-to-dismiss on the sheet resets the form for the next sale — same as tapping
            // "New sale" (the sale is already committed server-side; this only closes the summary).
            set: { presented in if !presented { vm.startNewSale() } }
        )
    }
}

// MARK: - Sale complete (ticket #77)

/// The Sale-complete summary sheet with the live invoice row. Bare-but-stable, stock SwiftUI:
/// the invoice resolves in place (PENDING → spinner, ISSUED → number + Open/Share, FAILED →
/// reassurance + Retry) as the CF issues the PDF. "New sale" is always available — a slow or
/// failed invoice must never read as though the sale failed (it's already committed).
private struct SaleCompleteView: View {
    @ObservedObject var vm: SalesViewModel
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TotalsRow(label: loc.t(Strings.shared.sales_success_customer), value: vm.selectedCustomer?.name ?? "—")
                    TotalsRow(label: loc.t(Strings.shared.sales_success_items), value: "\(vm.cartLines.count)")
                    TotalsRow(label: loc.t(Strings.shared.sales_success_total),
                              value: MoneyFormat.shared.format(amount: vm.totals.grandTotal, currency: vm.currency))
                    TotalsRow(label: loc.t(Strings.shared.sales_success_paid),
                              value: MoneyFormat.shared.format(amount: vm.amountPaid, currency: vm.currency))
                    TotalsRow(label: loc.t(Strings.shared.sales_success_balance),
                              value: MoneyFormat.shared.format(amount: vm.balanceRemaining, currency: vm.currency))
                }
                Section(loc.t(Strings.shared.sales_invoice_label)) { invoiceRow }
            }
            .navigationTitle(loc.t(Strings.shared.sales_success_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    // Labelled "Done" (ticket #106) — still calls startNewSale() (clears the cart)
                    // so the previous customer's cart never carries into the next sale.
                    Button(loc.t(Strings.shared.sales_success_done)) { vm.startNewSale() }
                }
            }
        }
    }

    @ViewBuilder private var invoiceRow: some View {
        switch vm.invoice.status {
        case .issued:
            Text(vm.invoice.number ?? "—").fontWeight(.semibold).lineLimit(1).truncationMode(.tail)
            if let raw = vm.invoice.url, let url = URL(string: raw) {
                Link(loc.t(Strings.shared.sales_invoice_open), destination: url)
                ShareLink(item: url) { Text(loc.t(Strings.shared.sales_invoice_share)) }
            }
        case .failed:
            Text(loc.t(Strings.shared.sales_invoice_failed)).foregroundStyle(.secondary)
            if vm.isRetryingInvoice {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(loc.t(Strings.shared.sales_invoice_retrying)).font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                if vm.invoiceRetryError {
                    Text(loc.t(Strings.shared.sales_invoice_retry_error))
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
                Button(loc.t(Strings.shared.sales_invoice_retry)) { vm.retryInvoice() }
                    .disabled(!vm.canRetryInvoice)
            }
        default: // pending / absent
            HStack(spacing: 8) {
                ProgressView()
                Text(loc.t(Strings.shared.sales_invoice_preparing)).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Cart line row

private struct CartLineRow: View {
    let line: CartLine
    let currency: String
    let hasError: Bool
    let enabled: Bool
    let priceLabel: String
    let discountLabel: String
    let netLabel: String
    let errorText: String
    let doneLabel: String
    let onPriceChange: (String) -> Void
    let onDiscountChange: (String) -> Void

    private var net: String {
        Money.shared.subtract(a: sanitized(line.unitPrice), b: sanitized(line.lineDiscount))
    }

    private func sanitized(_ value: String) -> String {
        let t = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return Money.shared.isValidPositiveDecimal(value: t) ? t : "0"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(line.kind == .inventory ? line.label : line.name)
                .lineLimit(1).truncationMode(.tail)
            if line.kind == .inventory {
                Text(line.imei).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(priceLabel).font(.caption2).foregroundStyle(.secondary)
                    MoneyField(value: line.unitPrice, doneLabel: doneLabel, onChange: onPriceChange)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(discountLabel).font(.caption2).foregroundStyle(.secondary)
                    MoneyField(value: line.lineDiscount, doneLabel: doneLabel, onChange: onDiscountChange)
                }
                VStack(alignment: .trailing, spacing: 2) {
                    Text(netLabel).font(.caption2).foregroundStyle(.secondary)
                    Text(MoneyFormat.shared.format(amount: net, currency: currency))
                }
            }
            .disabled(!enabled)
            if hasError {
                Text(errorText).font(.footnote).foregroundStyle(.red)
            }
        }
    }
}

// MARK: - Totals row

private struct TotalsRow: View {
    let label: String
    let value: String
    var emphasize: Bool = false

    var body: some View {
        HStack {
            Text(label).fontWeight(emphasize ? .semibold : .regular)
            Spacer()
            Text(value).fontWeight(emphasize ? .bold : .regular)
        }
    }
}

// MARK: - Money field (numeric keypad + Done accessory)

private struct MoneyField: View {
    let value: String
    let doneLabel: String
    let onChange: (String) -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        TextField("0", text: Binding(get: { value }, set: { onChange($0.filteredToDecimalInput()) }))
            .keyboardType(.decimalPad)
            .multilineTextAlignment(.trailing)
            .focused($isFocused)
            .toolbar {
                if isFocused {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button(doneLabel) { isFocused = false }
                    }
                }
            }
    }
}

private extension String {
    /// Keeps only digits and (at most) one decimal point — `.decimalPad` only hints a soft
    /// keyboard and doesn't stop a hardware/Bluetooth keyboard from typing letters.
    func filteredToDecimalInput() -> String {
        let kept = filter { $0.isNumber || $0 == "." }
        guard let firstDot = kept.firstIndex(of: ".") else { return kept }
        let afterDot = kept[kept.index(after: firstDot)...].replacingOccurrences(of: ".", with: "")
        return kept[..<kept.index(after: firstDot)] + afterDot
    }
}

// MARK: - Add custom line sheet

private struct AddCustomLineView: View {
    let currency: String
    let onAdd: (String, String) -> Void
    let onCancel: () -> Void

    @State private var name: String = ""
    @State private var price: String = ""
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        NavigationStack {
            Form {
                Section(loc.t(Strings.shared.sales_custom_name_label)) {
                    TextField(loc.t(Strings.shared.sales_custom_name_placeholder), text: $name)
                }
                Section(loc.t(Strings.shared.sales_custom_price_label, MoneyFormat.shared.symbolOf(currency: currency))) {
                    MoneyField(value: price, doneLabel: loc.t(Strings.shared.sales_picker_done)) { price = $0 }
                }
            }
            .navigationTitle(loc.t(Strings.shared.sales_custom_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.sales_custom_cancel)) { onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc.t(Strings.shared.sales_custom_add)) { onAdd(name.trimmingCharacters(in: .whitespacesAndNewlines), price.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
