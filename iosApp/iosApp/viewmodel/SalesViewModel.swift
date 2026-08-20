import Foundation
import SharedLogic

/// Submission lifecycle of a checkout (ticket #62). `.alreadySold` carries the offending
/// unit so its cart line can be flagged/removed; the flow never crashes on the race.
enum ConfirmState: Equatable {
    case idle
    case submitting
    case success(saleId: String)
    case alreadySold(imei: String, label: String)
    case error(message: String)
}

/// One cart line (ticket #62): an inventory unit (a serialized phone leaving stock, cost/
/// label/listPrice snapshotted from cache at add-time) or a revenue-only custom line. All
/// money is a decimal string; `id` is a stable lineId so per-line edits target the right row.
struct CartLine: Identifiable, Equatable {
    enum Kind { case inventory, custom }
    let id: String
    let kind: Kind
    // Inventory snapshot (empty for custom).
    var productId: String = ""
    var serialId: String = ""
    var imei: String = ""
    var label: String = ""
    var listPrice: String = "0"
    var cost: String = "0"
    // Custom.
    var name: String = ""
    // Common editable.
    var unitPrice: String
    var lineDiscount: String = "0"
}

/// The checkout gating errors (ticket #62). `canConfirm` is true only when every flag is
/// clear; `lineDiscountExceedsPrice` holds the offending lineIds for row highlighting.
struct SaleErrors: Equatable {
    var emptyCart = false
    var noCustomer = false
    var lineDiscountExceedsPrice: Set<String> = []
    /// Lines whose price was never typed. A SKU may be stocked unpriced (ticket #101), and a blank
    /// price sanitizes to "0" on the way to the ledger — so without this gate an unpriced phone
    /// rings up FREE. An explicit "0" is allowed: giving something away deliberately is a real
    /// transaction; only a never-typed price is the accident. Mirrors Desktop and Android.
    var unpricedLines: Set<String> = []
    var saleDiscountExceedsSubtotal = false
    var overpayment = false
    var walkInMustPayInFull = false
}

/// iOS checkout ViewModel (ticket #62): builds a cart from cached in-stock units, edits
/// prices/discounts, picks a customer or the Walk-in, takes a split payment, shows live
/// totals via the shared `SaleCalculator` (never re-implemented here), and confirms through
/// `RecordSaleUseCase` — mapping the "already sold" race to a graceful state. No screens.
///
/// Mirrors `AddStockViewModel`: manual DI in `bind`, the synthetic-default injection (here
/// the Walk-in Customer), and `startNewSale` preserving the cached inventory/entities/session
/// (the #58 reset-preserves-cache lesson). Derived totals/errors are computed properties so
/// they can never drift from the shared calculator. The unit-tested twin is the Desktop VM.
@MainActor
final class SalesViewModel: ObservableObject {
    // Session-derived.
    @Published private(set) var isLoading: Bool = true
    @Published private(set) var currency: String = ""
    @Published private(set) var taxConfig: TaxConfig =
        TaxConfig(gstEnabled: false, gstRate: "0", pstEnabled: false, pstRate: "0", isHST: false)
    /// Set when an admin changed the tax rate while this cart had items in it — the total moved
    /// under the cashier's hands, so the screen explains it rather than letting it be noticed.
    @Published private(set) var taxChangedMidSale: Bool = false

    // Item picker (cached, filtered client-side).
    @Published private(set) var allInStockUnits: [Serial] = []
    @Published private(set) var products: [Product] = []
    @Published var pickerSearchQuery: String = ""
    /// A location attributeId to narrow the picker; nil = all locations.
    @Published var pickerLocationFilter: String? = nil

    // Customer picker (cached, incl. injected Walk-in).
    @Published private(set) var allCustomers: [Entity] = []
    @Published var customerSearchQuery: String = ""
    @Published private(set) var selectedCustomer: Entity? = nil

    // Cart.
    @Published private(set) var cartLines: [CartLine] = []
    @Published private(set) var saleDiscount: String = "0"
    /// Tax-inclusive pricing toggle (ticket #106) — when true the typed prices already contain tax
    /// and the totals are computed by backing it out. Per sale, defaults to off, reset by
    /// `startNewSale` so it can never carry into the next customer.
    @Published private(set) var taxInclusive: Bool = false
    @Published private(set) var cashPaid: String = "0"
    @Published private(set) var cardPaid: String = "0"
    @Published private(set) var bankPaid: String = "0"
    @Published var note: String = ""

    // Walk-in buyer capture (ticket #77; only used when `isWalkIn`).
    @Published var buyerName: String = ""
    @Published var buyerPhone: String = ""

    // Customer tax number at checkout (ticket #106 follow-up): prefilled from the selected customer,
    // editable per sale, snapshotted onto the sale. "Save to contact" persists it back.
    @Published var buyerTaxNumber: String = ""
    @Published private(set) var savingTaxNumber: Bool = false
    @Published private(set) var taxNumberSaved: Bool = false
    @Published private(set) var taxNumberSaveError: Bool = false

    // Customer contact phone at checkout (mirrors the tax-number field): prefilled from the
    // selected customer's primary number, editable per sale, snapshotted onto the sale.
    @Published var buyerContactPhone: String = ""
    @Published private(set) var savingPhone: Bool = false
    @Published private(set) var phoneSaved: Bool = false
    @Published private(set) var phoneSaveError: Bool = false

    // Submission.
    @Published private(set) var confirmState: ConfirmState = .idle

    // Invoice (ticket #77): observed live off the sale doc after a successful sale.
    @Published private(set) var invoice: SaleInvoice = SaleInvoice(status: .pending, number: nil, url: nil, error: nil)
    /// True while a manual Retry is in flight — disables the button so it can't be re-clicked.
    @Published private(set) var isRetryingInvoice: Bool = false
    /// True when the last manual Retry call itself couldn't reach the invoice service (callable
    /// missing / unauthenticated / functions not deployed) — the live stream never settles in that
    /// case, so we surface an inline reassurance instead of a silent no-op. Cleared on the next tap.
    @Published private(set) var invoiceRetryError: Bool = false

    // Mirrors shared WALK_IN_CUSTOMER_ID / _NAME (kept in sync deliberately — the fixed id
    // also lives in the Cloud Function + Firestore rules, exactly like AddStock's supplier).
    private let walkInCustomerId = "walk-in-customer"
    private let walkInCustomerName = "Walk-in Customer"

    private var session: UserSession? = nil
    private var config: FirebaseClientConfig? = nil
    private var salesRepo: BackendSalesRepository? = nil
    private var recordSaleUseCase: RecordSaleUseCase? = nil
    private var saveBuyerTaxNumberUseCase: SaveBuyerTaxNumberUseCase? = nil
    private var saveBuyerPhoneUseCase: SaveBuyerPhoneUseCase? = nil
    private var observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = nil
    private var retryInvoiceUseCase: RetryInvoiceUseCase? = nil
    private var serialsTask: Task<Void, Never>? = nil
    private var productsTask: Task<Void, Never>? = nil
    private var customersTask: Task<Void, Never>? = nil
    /// Follows companySettings/profile so a rate change reaches this till within seconds.
    private var profileTask: Task<Void, Never>? = nil
    /// The live invoice observation for the just-completed sale; cancelled on new-sale/rebind.
    private var invoiceTask: Task<Void, Never>? = nil
    private var customSeq: Int = 0

    // MARK: - Binding

    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, self.config == config { return }
        self.session = session
        self.config = config
        self.currency = session.currency
        self.taxConfig = session.tax
        self.isLoading = true

        let sales = BackendSalesRepository(config: config)
        let inv = BackendInventoryRepository(config: config, uid: session.uid)
        let entities = BackendEntityRepository(config: config, uid: session.uid)
        self.salesRepo = sales
        self.recordSaleUseCase = RecordSaleUseCase(repository: sales)
        self.saveBuyerTaxNumberUseCase = SaveBuyerTaxNumberUseCase(repository: entities)
        self.saveBuyerPhoneUseCase = SaveBuyerPhoneUseCase(repository: entities)
        self.observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(repository: sales)
        self.retryInvoiceUseCase = RetryInvoiceUseCase(repository: sales)

        // The tax rate an admin can change from Settings. `session.tax` is captured at sign-in and
        // never refreshed, so without this a rate changed at noon would leave this till charging
        // the old one all day, with nothing on screen saying so. A stream failure keeps the rate we
        // already have — a stale rate still beats no checkout.
        profileTask?.cancel()
        profileTask = Task { [weak self] in
            do {
                let settings = BackendCompanySettingsRepository(config: config)
                let flow = ObserveCompanyProfileUseCase(repository: settings).execute()
                for try await profile in flow {
                    guard let self = self, profile.tax != self.taxConfig else { continue }
                    self.taxConfig = profile.tax
                    // Only worth flagging if it changes a total someone can already see.
                    self.taxChangedMidSale = !self.cartLines.isEmpty
                }
            } catch {
                // Keep the rate we have.
            }
        }

        // Live reads: stock changes as units sell, the customer list changes as parties are
        // added — the cart is filtered/derived client-side (the ViewModel is the cache).
        serialsTask?.cancel()
        serialsTask = Task { [weak self] in
            do {
                let flow = try ObserveInventoryUseCase(repository: inv).observeInStockSerials(session: session)
                for try await list in flow {
                    self?.allInStockUnits = list
                    self?.isLoading = false
                }
            } catch {
                self?.isLoading = false
            }
        }
        productsTask?.cancel()
        productsTask = Task { [weak self] in
            do {
                let flow = try ObserveInventoryUseCase(repository: inv)
                    .observeProducts(session: session, includeArchived: false)
                for try await list in flow { self?.products = list }
            } catch {
                // Leave products empty; the picker still lists units by IMEI.
            }
        }
        customersTask?.cancel()
        customersTask = Task { [weak self] in
            do {
                let flow = try ObserveEntitiesUseCase(repository: entities)
                    .execute(session: session, includeArchived: false)
                for try await list in flow { self?.allCustomers = list }
            } catch {
                // Leave list empty; the injected Walk-in is still selectable.
            }
        }
    }

    // MARK: - Derived state (computed → always fresh, never drifts from SaleCalculator)

    var isWalkIn: Bool {
        guard let c = selectedCustomer else { return false }
        return c.isWalkIn || c.id == walkInCustomerId
    }

    private var cartSerialIds: Set<String> {
        Set(cartLines.filter { $0.kind == .inventory }.map { $0.serialId })
    }

    private var lineInputs: [SaleLineInput] {
        cartLines.map { line in
            switch line.kind {
            case .inventory:
                return SaleLineInputInventoryLineInput(
                    productId: line.productId, serialId: line.serialId,
                    unitPrice: money(line.unitPrice), lineDiscount: money(line.lineDiscount))
            case .custom:
                return SaleLineInputCustomLineInput(
                    name: line.name, unitPrice: money(line.unitPrice), lineDiscount: money(line.lineDiscount))
            }
        }
    }

    var totals: SaleTotals {
        var costBySerialId: [String: String] = [:]
        for line in cartLines where line.kind == .inventory { costBySerialId[line.serialId] = money(line.cost) }
        return SaleCalculator.shared.compute(
            lines: lineInputs, saleDiscount: money(saleDiscount), tax: taxConfig,
            costBySerialId: costBySerialId, taxInclusive: taxInclusive)
    }

    var amountPaid: String { Money.shared.sum(values: [money(cashPaid), money(cardPaid), money(bankPaid)]) }

    var balanceRemaining: String { Money.shared.subtract(a: totals.grandTotal, b: amountPaid) }

    var errors: SaleErrors {
        let t = totals
        let paid = amountPaid
        var e = SaleErrors()
        e.emptyCart = cartLines.isEmpty
        e.noCustomer = selectedCustomer == nil
        e.lineDiscountExceedsPrice = Set(
            cartLines.filter { !Money.shared.lessThanOrEqual(a: money($0.lineDiscount), b: money($0.unitPrice)) }
                .map { $0.id })
        e.unpricedLines = Set(
            cartLines.filter { $0.unitPrice.trimmingCharacters(in: .whitespaces).isEmpty }
                .map { $0.id })
        e.saleDiscountExceedsSubtotal = !Money.shared.lessThanOrEqual(a: money(saleDiscount), b: t.subtotal)
        e.overpayment = Money.shared.compare(a: paid, b: t.grandTotal) > 0
        e.walkInMustPayInFull = isWalkIn && !cartLines.isEmpty && Money.shared.compare(a: paid, b: t.grandTotal) != 0
        return e
    }

    var canConfirm: Bool {
        if case .submitting = confirmState { return false }
        let e = errors
        return !cartLines.isEmpty && selectedCustomer != nil &&
            e.lineDiscountExceedsPrice.isEmpty && e.unpricedLines.isEmpty &&
            !e.saleDiscountExceedsSubtotal &&
            !e.overpayment && !e.walkInMustPayInFull
    }

    /// The Retry affordance shows only for a FAILED invoice, and is disabled mid-retry.
    var canRetryInvoice: Bool { invoice.status == .failed && !isRetryingInvoice }

    /// Picker rows: in-stock/active units, minus cart units, narrowed by location + search.
    var visibleUnits: [Serial] {
        let inCart = cartSerialIds
        let q = pickerSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        return allInStockUnits.filter { serial in
            guard serial.status == .inStock, serial.isActive else { return false }
            guard !inCart.contains(serial.serialId) else { return false }
            if let loc = pickerLocationFilter, serial.location.attributeId != loc { return false }
            if q.isEmpty { return true }
            return serial.imei.localizedCaseInsensitiveContains(q) ||
                label(for: serial).localizedCaseInsensitiveContains(q)
        }
    }

    /// Company parties as selectable customers — always incl. the reserved Walk-in Customer,
    /// customers sorted first, then filtered by the search query.
    var customerOptions: [Entity] {
        var list = allCustomers
        if !list.contains(where: { $0.id == walkInCustomerId }) {
            list.append(makeWalkIn())
        }
        let q = customerSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        return list
            .filter { $0.isActive }
            .filter { q.isEmpty || $0.name.localizedCaseInsensitiveContains(q) }
            .sorted { a, b in
                let aCust = a.roles.contains(EntityRole.customer)
                let bCust = b.roles.contains(EntityRole.customer)
                if aCust != bCust { return aCust }
                return a.name.lowercased() < b.name.lowercased()
            }
    }

    /// Human-readable SKU label for a serial, from its cached product (falls back to IMEI).
    func label(for serial: Serial) -> String {
        guard let product = products.first(where: { $0.productId == serial.productId }) else { return serial.imei }
        let parts = AttributeType.companion.SKU_DEFINING.compactMap { product.attributes[$0]?.name }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? serial.imei : parts.joined(separator: " · ")
    }

    // MARK: - Item picker actions

    func onPickerSearchChanged(_ query: String) { pickerSearchQuery = query }
    func onPickerLocationFilterChanged(_ locationId: String?) { pickerLocationFilter = locationId }

    /// Add an in-stock unit to the cart, snapshotting its cost/label/listPrice/imei from the
    /// cached Serial + Product now (T1 won't re-read products). Default unitPrice = list price.
    func addUnitToCart(serialId: String) {
        if cartSerialIds.contains(serialId) { return }
        guard let serial = allInStockUnits.first(where: { $0.serialId == serialId }) else { return }
        let product = products.first(where: { $0.productId == serial.productId })
        let listPrice = product?.defaultSellingPrice ?? "0"
        cartLines.append(CartLine(
            id: "inv:\(serialId)", kind: .inventory,
            productId: serial.productId, serialId: serialId, imei: serial.imei,
            label: label(for: serial), listPrice: listPrice, cost: serial.cost,
            name: "", unitPrice: listPrice, lineDiscount: "0"))
    }

    /// Add a revenue-only ad-hoc line (a case, a fee) — no stock, no cost-of-goods.
    func addCustomLine(name: String, price: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        customSeq += 1
        cartLines.append(CartLine(
            id: "custom:\(customSeq)", kind: .custom,
            name: trimmed, unitPrice: money(price), lineDiscount: "0"))
    }

    // MARK: - Cart line edits

    func setUnitPrice(lineId: String, price: String) { mutateLine(lineId) { $0.unitPrice = price } }
    func setLineDiscount(lineId: String, discount: String) { mutateLine(lineId) { $0.lineDiscount = discount } }
    func removeLine(lineId: String) { cartLines.removeAll { $0.id == lineId } }
    func setSaleDiscount(_ discount: String) { saleDiscount = discount }

    /// Flip tax-inclusive pricing for this sale (ticket #106); the totals recompute automatically.
    func setTaxInclusive(_ inclusive: Bool) { taxInclusive = inclusive }

    private func mutateLine(_ lineId: String, _ transform: (inout CartLine) -> Void) {
        guard let idx = cartLines.firstIndex(where: { $0.id == lineId }) else { return }
        var line = cartLines[idx]
        transform(&line)
        cartLines[idx] = line
    }

    // MARK: - Customer picker actions

    func onCustomerSearchChanged(_ query: String) { customerSearchQuery = query }
    func selectCustomer(_ customer: Entity) {
        selectedCustomer = customer
        // Prefill the tax-number field from the picked customer (ticket #106 follow-up).
        buyerTaxNumber = customer.taxNumber ?? ""
        taxNumberSaved = false
        taxNumberSaveError = false
        // Prefill the phone field from the customer's primary number (mirrors the tax field).
        buyerContactPhone = customer.phones.first ?? ""
        phoneSaved = false
        phoneSaveError = false
    }

    /// Select the reserved Walk-in Customer (forces pay-in-full gating).
    func selectWalkIn() {
        selectedCustomer = allCustomers.first(where: { $0.id == walkInCustomerId }) ?? makeWalkIn()
        // A walk-in has no contact; start the tax field empty (still usable for the bill). The
        // walk-in phone is captured by the separate Bill-To phone field, so leave this one empty.
        buyerTaxNumber = ""
        taxNumberSaved = false
        taxNumberSaveError = false
        buyerContactPhone = ""
        phoneSaved = false
        phoneSaveError = false
    }

    // MARK: - Customer tax number (ticket #106 follow-up)

    /// Whether "Save to contact" is offered: a named customer is selected, the user can manage
    /// profiles, the field differs from the stored value, and no save is in flight.
    var canSaveTaxToContact: Bool {
        guard let customer = selectedCustomer, !isWalkIn, !savingTaxNumber else { return false }
        guard session?.permissions.profiles == .manage else { return false }
        return buyerTaxNumber.trimmingCharacters(in: .whitespaces) != (customer.taxNumber ?? "")
    }

    func setBuyerTaxNumber(_ value: String) {
        buyerTaxNumber = value
        taxNumberSaved = false
        taxNumberSaveError = false
    }

    // MARK: - Customer phone (mirrors the tax-number field)

    /// The phone twin of `canSaveTaxToContact` — compares against the stored primary number.
    var canSavePhoneToContact: Bool {
        guard let customer = selectedCustomer, !isWalkIn, !savingPhone else { return false }
        guard session?.permissions.profiles == .manage else { return false }
        return buyerContactPhone.trimmingCharacters(in: .whitespaces) != (customer.phones.first ?? "")
    }

    func setBuyerContactPhone(_ value: String) {
        buyerContactPhone = value
        phoneSaved = false
        phoneSaveError = false
    }

    /// Persist the current phone as the selected customer's *primary* number, preserving any
    /// secondary numbers. Gated on `profiles` MANAGE in the use case; the mirror of
    /// `saveBuyerTaxNumberToContact`. On success the cached customer is updated so the button settles.
    func saveBuyerPhoneToContact() {
        guard let session = session, let useCase = saveBuyerPhoneUseCase,
              let customer = selectedCustomer, canSavePhoneToContact else { return }
        let trimmed = buyerContactPhone.trimmingCharacters(in: .whitespaces)
        // Edit only the primary number; keep any others the contact already has.
        var newPhones = Array(customer.phones.dropFirst())
        if !trimmed.isEmpty { newPhones.insert(trimmed, at: 0) }
        savingPhone = true
        phoneSaved = false
        phoneSaveError = false
        Task {
            do {
                try await useCase.execute(session: session, entityId: customer.id, phones: newPhones)
                // Rebuild with the new phones (SKIE doesn't surface Kotlin `copy` defaults, so pass
                // every field — same pattern as saveBuyerTaxNumberToContact()).
                let updated = Entity(
                    id: customer.id, name: customer.name, phones: newPhones, email: customer.email,
                    address: customer.address, roles: customer.roles, notes: customer.notes,
                    taxNumber: customer.taxNumber, isWalkIn: customer.isWalkIn, isActive: customer.isActive,
                    hlCustomerId: customer.hlCustomerId, hlAccountId: customer.hlAccountId,
                    syncStatus: customer.syncStatus)
                savingPhone = false
                phoneSaved = true
                if selectedCustomer?.id == customer.id { selectedCustomer = updated }
                allCustomers = allCustomers.map { $0.id == customer.id ? updated : $0 }
            } catch {
                savingPhone = false
                phoneSaveError = true
            }
        }
    }

    /// Persist the current tax number onto the selected named customer (gated on `profiles` MANAGE in
    /// the use case). On success the cached customer is updated so the button settles.
    func saveBuyerTaxNumberToContact() {
        guard let session = session, let useCase = saveBuyerTaxNumberUseCase,
              let customer = selectedCustomer, canSaveTaxToContact else { return }
        let trimmed = buyerTaxNumber.trimmingCharacters(in: .whitespaces)
        let value: String? = trimmed.isEmpty ? nil : trimmed
        savingTaxNumber = true
        taxNumberSaved = false
        taxNumberSaveError = false
        Task {
            do {
                try await useCase.execute(session: session, entityId: customer.id, taxNumber: value)
                // Rebuild with the new taxNumber (SKIE doesn't surface Kotlin `copy` defaults, so pass
                // every field — same pattern as makeWalkIn()).
                let updated = Entity(
                    id: customer.id, name: customer.name, phones: customer.phones, email: customer.email,
                    address: customer.address, roles: customer.roles, notes: customer.notes,
                    taxNumber: value, isWalkIn: customer.isWalkIn, isActive: customer.isActive,
                    hlCustomerId: customer.hlCustomerId, hlAccountId: customer.hlAccountId,
                    syncStatus: customer.syncStatus)
                savingTaxNumber = false
                taxNumberSaved = true
                if selectedCustomer?.id == customer.id { selectedCustomer = updated }
                allCustomers = allCustomers.map { $0.id == customer.id ? updated : $0 }
            } catch {
                savingTaxNumber = false
                taxNumberSaveError = true
            }
        }
    }

    // MARK: - Payment + note

    func setCash(_ amount: String) { cashPaid = amount }
    func setCard(_ amount: String) { cardPaid = amount }
    func setBank(_ amount: String) { bankPaid = amount }
    func setNote(_ value: String) { note = value }

    // MARK: - Walk-in buyer capture (ticket #77)
    // Free-text; no gating (never blocks Confirm). The use case keeps these only for a
    // walk-in and trims blank → nil → the CF falls back to "Walk-in Customer".
    func setBuyerName(_ value: String) { buyerName = value }
    /// Phone is digits-only, capped at 10 — a keyboard hint alone can't stop paste/hardware input.
    func setBuyerPhone(_ value: String) { buyerPhone = String(value.filter { $0.isNumber }.prefix(10)) }

    // MARK: - Submission

    /// Build a `SaleInput` + `ResolvedSaleLine` snapshots from the cart and commit through
    /// `RecordSaleUseCase`. Maps success → `.success`; an `AlreadySoldException` → `.alreadySold`
    /// **and flags/removes the offending line**; permission/validation/network → `.error`. Never
    /// crashes (a Kotlin exception surfaces as an NSError we inspect, not a fatal trap).
    func confirmSale() {
        guard let session = session, let useCase = recordSaleUseCase, canConfirm else { return }

        let lines = lineInputs
        let resolved: [ResolvedSaleLine] = cartLines.filter { $0.kind == .inventory }.map {
            ResolvedSaleLine(serialId: $0.serialId, imei: $0.imei, label: $0.label, listPrice: $0.listPrice, cost: $0.cost)
        }
        guard let customer = selectedCustomer else { return }
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        // SKIE doesn't surface Kotlin default params to Swift, so every field is passed explicitly
        // (the use case keeps buyerName/buyerPhone/buyerTaxNumber per the walk-in vs named rule).
        let sale = SaleInput(
            customerEntityId: customer.id,
            isWalkIn: isWalkIn,
            lines: lines,
            saleDiscount: money(saleDiscount),
            payment: PaymentInput(cash: money(cashPaid), card: money(cardPaid), bank: money(bankPaid)),
            note: trimmedNote.isEmpty ? nil : trimmedNote,
            buyerName: buyerName,
            // Walk-in: the Bill-To phone field. Named customer: the contact-phone field, prefilled
            // from their Entity and editable for this sale (mirrors buyerTaxNumber). Never both.
            buyerPhone: isWalkIn ? buyerPhone : buyerContactPhone.trimmingCharacters(in: .whitespaces),
            // The business date (ticket #107). iOS has no date picker yet, so a phone always
            // records today — correct for a sale rung up at the counter. Backdating is Desktop-only
            // for now; when the picker lands here, this reads from the screen's state.
            saleDate: Int64(Date().timeIntervalSince1970 * 1000),
            taxInclusive: taxInclusive,
            // The per-sale tax number as edited at checkout (prefilled from the customer); carried
            // for a walk-in too. Blank → nil in the use case.
            buyerTaxNumber: buyerTaxNumber)

        confirmState = .submitting
        Task {
            do {
                // SKIE doesn't surface Kotlin default params, so taxConfig (default session.tax in
                // the use case) must be passed explicitly — the live value this screen is showing.
                // SKIE surfaces no Kotlin defaults, so `now` (the clock the future-date guard
                // checks against) is passed explicitly like taxConfig above.
                let saleId = try await useCase.execute(
                    session: session, sale: sale, resolved: resolved, taxConfig: taxConfig,
                    now: Int64(Date().timeIntervalSince1970 * 1000))
                self.confirmState = .success(saleId: saleId)
                self.invoice = SaleInvoice(status: .pending, number: nil, url: nil, error: nil)
                self.isRetryingInvoice = false
                self.observeInvoice(saleId: saleId)
            } catch {
                let ns = error as NSError
                if let already = ns.kotlinException as? AlreadySoldException {
                    let imei = already.imei
                    let offending = self.cartLines.first { $0.kind == .inventory && $0.imei == imei }
                    self.cartLines.removeAll { $0.id == offending?.id }
                    self.confirmState = .alreadySold(imei: imei, label: offending?.label ?? imei)
                } else if ns.kotlinException is PermissionDeniedException {
                    self.confirmState = .error(message: "You don't have permission to record sales.")
                } else {
                    self.confirmState = .error(message: self.describe(error))
                }
            }
        }
    }

    /// Dismiss a terminal confirm state (after showing the AlreadySold/Error message).
    func dismissConfirmState() { confirmState = .idle }

    // MARK: - Invoice (ticket #77)

    /// Observe the just-completed sale's invoice live so the Sale-complete row resolves in place
    /// (PENDING → ISSUED/FAILED) as the CF issues the PDF. Errors are swallowed — a stalled stream
    /// simply leaves the row "preparing"; it must never make a committed sale read as failed.
    private func observeInvoice(saleId: String) {
        guard let useCase = observeSaleInvoiceUseCase else { return }
        invoiceTask?.cancel()
        invoiceTask = Task { [weak self] in
            do {
                let flow = try useCase.execute(saleId: saleId)
                for try await inv in flow {
                    self?.invoice = inv
                }
            } catch {
                // Leave the row "preparing"; the sale itself is already committed.
            }
        }
    }

    /// Cashier-facing Retry for a FAILED invoice: re-issue **now** instead of waiting for the
    /// reconcile sweep. Optimistically shows "preparing" and locks the button (single-click); the
    /// live `observeInvoice` stream carries the settled result — ISSUED hides the button, a fresh
    /// FAILED re-enables it (the lock clears when the call returns). Never touches the sale itself.
    func retryInvoice() {
        guard case let .success(saleId) = confirmState, let useCase = retryInvoiceUseCase, canRetryInvoice else { return }
        isRetryingInvoice = true
        invoiceRetryError = false
        invoice = SaleInvoice(status: .pending, number: invoice.number, url: invoice.url, error: nil)
        Task {
            do {
                _ = try await useCase.execute(saleId: saleId)
            } catch {
                // Roll the optimistic PENDING back to FAILED and explain — *unless* the live
                // stream has already settled. A client-side failure does not mean the server did
                // nothing: the callable client gives up well before the CF's 120 s budget, so a
                // slow-but-successful re-issue lands ISSUED on the doc while this throws.
                // Overwriting that with FAILED would hide a finished invoice behind the retry UI,
                // and nothing would correct it — no further doc write is coming.
                if !self.invoice.hasSettled {
                    self.invoiceRetryError = true
                    self.invoice = SaleInvoice(status: .failed, number: self.invoice.number, url: self.invoice.url, error: nil)
                }
            }
            self.isRetryingInvoice = false
        }
    }

    /// Clear the cart/customer/payments/note for the next sale but **preserve** the cached
    /// inventory/customers/products + session/currency/tax — the observed lists won't re-emit
    /// just because the form cleared, so wiping them would empty the pickers (the #58 lesson).
    func startNewSale() {
        invoiceTask?.cancel()
        invoiceTask = nil
        pickerSearchQuery = ""
        pickerLocationFilter = nil
        customerSearchQuery = ""
        selectedCustomer = nil
        cartLines = []
        taxChangedMidSale = false
        saleDiscount = "0"
        // Reset to tax-exclusive so a toggle flipped for one customer can't silently
        // under-charge tax on the next sale (ticket #106).
        taxInclusive = false
        buyerTaxNumber = ""
        savingTaxNumber = false
        taxNumberSaved = false
        taxNumberSaveError = false
        buyerContactPhone = ""
        savingPhone = false
        phoneSaved = false
        phoneSaveError = false
        cashPaid = "0"; cardPaid = "0"; bankPaid = "0"
        note = ""
        buyerName = ""
        buyerPhone = ""
        confirmState = .idle
        invoice = SaleInvoice(status: .pending, number: nil, url: nil, error: nil)
        isRetryingInvoice = false
        invoiceRetryError = false
    }

    // MARK: - Helpers

    /// A blank/invalid money field displays as "0" for live math (the UI feeds partial text
    /// while typing); `RecordSaleUseCase` does the authoritative validation at confirm time.
    private func money(_ value: String) -> String {
        let t = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return Money.shared.isValidPositiveDecimal(value: t) ? t : "0"
    }

    private func makeWalkIn() -> Entity {
        Entity(id: walkInCustomerId, name: walkInCustomerName, phones: [], email: nil, address: nil,
               roles: [EntityRole.customer], notes: nil, taxNumber: nil, isWalkIn: true, isActive: true,
               hlCustomerId: nil, hlAccountId: nil, syncStatus: .pending)
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.kotlinException as? KotlinThrowable { return throwable.message ?? "Sale failed" }
        return ns.localizedDescription
    }
}
