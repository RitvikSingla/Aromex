import Foundation
import SharedLogic

enum ImeiCheckState { case idle, checking, available, alreadyInBatch, alreadyInStock, invalid }
enum AddInventoryRoute { case entry, paste, review }

/// One SICKW block the parser could not read — mirrors shared `UnreadableBlock`,
/// held as a value type so review rows can dismiss individual blocks.
struct UnreadableItem: Identifiable {
    let id = UUID()
    let rawText: String
    let reason: UnreadableBlock.Reason
}

/// Per-row live IMEI status shown inline on the review table.
enum RowImeiStatus { case ok, checking, dupInBatch, inStock }

/// The user's decision on one proposed `CommissionLine` in the intake dialog (ticket #97): kept
/// (`included`) or skipped, its `amount` editable (`overridden` when it no longer equals the
/// rule's figure), and either added to the payee's balance or **given now** (`giveNow`) as a
/// `cash` + `bank` split (mirroring the inventory-supplier UI). Keyed by the line's ruleId.
struct CommissionDecisionState {
    var included: Bool = true
    var giveNow: Bool = false
    var cash: String = ""
    var bank: String = ""
    var amount: String
    var overridden: Bool = false

    /// Sum given now (cash + bank), as a decimal string.
    var givenNow: String {
        Money.shared.add(a: cash.isEmpty ? "0" : cash, b: bank.isEmpty ? "0" : bank)
    }

    /// What stays on the payee's balance after giving now: owed − given (≥ 0 when not exceeding).
    var leftOnBalance: String {
        Money.shared.subtract(a: amount.isEmpty ? "0" : amount, b: givenNow)
    }

    /// True when the give-now split exceeds the amount owed — blocks confirm.
    var giveExceedsAmount: Bool {
        giveNow && !Money.shared.lessThanOrEqual(a: givenNow, b: amount.isEmpty ? "0" : amount)
    }
}

struct ReviewUnit: Identifiable {
    let id = UUID()
    var imei: String
    var cost: String
    var condition: Condition
    var location: AttributeRef
    var attributes: [AttributeType: AttributeRef] = [:]
    var sellingPrice: String = ""
    /// True when this row came from a SICKW paste (drives status colours + "new" tags).
    var fromSickw: Bool = false

    var skuKey: String {
        attributes.keys
            .sorted { $0.wire < $1.wire }
            .compactMap { t -> String? in
                guard let ref = attributes[t] else { return nil }
                return "\(t.wire):\(ref.attributeId)"
            }
            .joined(separator: ",")
    }
}

/// Two-screen add-stock/add-units ViewModel.
///
/// Screen 1 (entry): pick SKU attributes, set batch defaults (cost/condition/location),
/// type/scan IMEIs one at a time with live duplicate detection, then tap Review.
/// Screen 2 (review): edit per-unit overrides, confirm → save.
@MainActor
final class AddStockViewModel: ObservableObject {
    @Published private(set) var attributes: [AttributeValue] = []
    @Published private(set) var picked: [AttributeType: AttributeRef] = [:]
    @Published var defaultSellingPrice: String = ""
    @Published var batchCost: String = ""
    @Published var batchCondition: Condition = .theNew
    @Published private(set) var batchLocation: AttributeRef? = nil
    @Published var pendingImei: String = ""
    @Published private(set) var imeiCheckState: ImeiCheckState = .idle
    @Published private(set) var stagedImeis: [String] = []
    @Published private(set) var route: AddInventoryRoute = .entry
    @Published private(set) var reviewUnits: [ReviewUnit] = []
    @Published private(set) var existingProductId: String? = nil
    @Published private(set) var attributesLocked: Bool = false
    @Published private(set) var saving: Bool = false
    @Published var error: String? = nil
    @Published private(set) var done: Bool = false

    // MARK: - Purchase dialog (#58)

    @Published var showPurchaseDialog: Bool = false
    /// All company parties for the bought-from dropdown; suppliers sorted first below.
    @Published private(set) var entities: [Entity] = []
    /// The chosen party; defaults to the reserved Unspecified Supplier when the dialog opens.
    @Published var purchaseParty: AttributeRef? = nil
    @Published var purchaseCash: String = ""
    @Published var purchaseBank: String = ""
    /// Company currency for the money fields, captured on bind.
    @Published private(set) var currency: String = ""

    // MARK: - Commission on intake (#97)

    /// Active rules streamed for the logged-in user (empty if none / no access).
    @Published private(set) var activeCommissionRules: [CommissionRule] = []
    /// Lines proposed for the current batch, computed when the purchase dialog opens.
    @Published private(set) var commissionLines: [CommissionLine] = []
    /// Per-line user decision, keyed by the line's ruleId.
    @Published private(set) var commissionDecisions: [String: CommissionDecisionState] = [:]

    // Mirrors shared UNSPECIFIED_SUPPLIER_ID / _NAME (kept in sync deliberately — the
    // fixed id also lives in the Cloud Function + Firestore rules).
    private let unspecifiedSupplierId = "unspecified-supplier"
    private let unspecifiedSupplierName = "Unspecified Supplier"

    // MARK: - SICKW paste flow (#53)

    /// The raw SICKW text the user pastes. Lives in the VM so navigating away and
    /// back never loses it (paste is a screen, not a transient sheet).
    @Published var pasteText: String = ""
    @Published private(set) var parsing: Bool = false
    /// A non-nil value shows the "nothing could be read" state on the paste screen.
    @Published var pasteError: String? = nil
    /// Blocks the parser could not turn into a phone — surfaced on review, never dropped.
    @Published private(set) var unreadable: [UnreadableItem] = []
    /// Ids of vocabulary values minted during resolve → cells tagged "new".
    @Published private(set) var newlyCreatedIds: Set<String> = []
    /// Populated after a parse so the review screen can show the summary banner once.
    @Published var parseSummary: String? = nil
    /// Live per-row IMEI status keyed by ReviewUnit id (in-batch dup / already in stock).
    @Published private(set) var rowImeiStatus: [UUID: RowImeiStatus] = [:]

    /// Max units the atomic save can take — surfaced to the confirm bar for the cap dialog.
    var safeBatchCeiling: Int { Int(InventoryLimits.shared.SAFE_BATCH_CEILING) }
    var exceedsBatchCap: Bool { InventoryLimits.shared.exceedsBatchCap(unitCount: Int32(reviewUnits.count)) }

    var hasUnsavedChanges: Bool {
        !pendingImei.isEmpty || !stagedImeis.isEmpty || !reviewUnits.isEmpty ||
        !picked.isEmpty || !batchCost.isEmpty || batchLocation != nil ||
        !pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var session: UserSession? = nil
    private var config: FirebaseClientConfig? = nil
    private var invRepo: BackendInventoryRepository? = nil
    private var attrRepo: BackendAttributeRepository? = nil
    private var entityRepo: BackendEntityRepository? = nil
    private var commissionRepo: BackendCommissionRuleRepository? = nil
    private var attributesTask: Task<Void, Never>? = nil
    private var entitiesTask: Task<Void, Never>? = nil
    private var commissionTask: Task<Void, Never>? = nil
    private var checkTask: Task<Void, Never>? = nil

    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, self.config == config { return }
        self.session = session
        self.config = config
        self.currency = session.currency
        let inv = BackendInventoryRepository(config: config, uid: session.uid)
        let attrs = BackendAttributeRepository(config: config, uid: session.uid)
        let entities = BackendEntityRepository(config: config, uid: session.uid)
        let commissionRules = BackendCommissionRuleRepository(config: config, uid: session.uid)
        self.invRepo = inv
        self.attrRepo = attrs
        self.entityRepo = entities
        self.commissionRepo = commissionRules
        attributesTask?.cancel()
        attributesTask = Task { [weak self] in
            do {
                for try await list in attrs.observeAttributes() {
                    self?.attributes = list
                }
            } catch {
                self?.error = (error as NSError).localizedDescription
            }
        }

        // Live party list for the bought-from dropdown, gated on `inventory` (not `profiles`)
        // so anyone who can add stock sees the supplier list. Falls back to just the
        // Unspecified Supplier default if even inventory access is missing.
        entitiesTask?.cancel()
        entitiesTask = Task { [weak self] in
            do {
                let flow = try ObservePartiesForPurchaseUseCase(repository: entities)
                    .execute(session: session)
                for try await list in flow {
                    self?.entities = list
                }
            } catch {
                // inventory access missing or stream error → leave list empty; default still works.
            }
        }

        // Active commission rules (ticket #97) — gated on inventory access so the cashier who
        // adds stock sees each proposed commission at intake. No access / no rules → empty, and
        // the commission section simply never appears.
        commissionTask?.cancel()
        commissionTask = Task { [weak self] in
            do {
                let flow = try ObserveActiveCommissionRulesUseCase(repository: commissionRules)
                    .execute(session: session)
                for try await list in flow {
                    self?.activeCommissionRules = list
                }
            } catch {
                // no access / stream error → leave rules empty; the section just won't show.
            }
        }
    }

    func options(_ type: AttributeType) -> [AttributeValue] {
        let all = attributes.filter { $0.type == type && $0.isActive }
        guard type == AttributeType.model else { return all }
        guard let brandId = picked[AttributeType.brand]?.attributeId else { return [] }
        return all.filter { $0.parentId == brandId }
    }

    func pick(_ type: AttributeType, _ value: AttributeValue) {
        picked[type] = AttributeRef(attributeId: value.attributeId, name: value.name)
        if type == AttributeType.brand { picked[AttributeType.model] = nil }
    }

    func clearPick(_ type: AttributeType) {
        picked[type] = nil
        if type == AttributeType.brand { picked[AttributeType.model] = nil }
    }

    func setBatchLocation(_ value: AttributeValue) {
        batchLocation = AttributeRef(attributeId: value.attributeId, name: value.name)
    }

    func clearBatchLocation() { batchLocation = nil }

    func addNewAttribute(_ type: AttributeType, name: String) {
        guard let session = session, let repo = attrRepo else { return }
        let parentId = (type == AttributeType.model) ? picked[AttributeType.brand]?.attributeId : nil
        Task {
            do {
                let id = try await AddAttributeUseCase(repository: repo)
                    .execute(session: session, type: type, name: name, parentId: parentId)
                let ref = AttributeRef(attributeId: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines))
                if type == AttributeType.location {
                    self.batchLocation = ref
                } else {
                    self.picked[type] = ref
                    if type == AttributeType.brand { self.picked[AttributeType.model] = nil }
                }
            } catch {
                self.error = (error as NSError).localizedDescription
            }
        }
    }

    func checkAndAddImei() {
        let imei = pendingImei.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !imei.isEmpty else { return }
        guard Self.isValidImei(imei) else { imeiCheckState = .invalid; return }
        if stagedImeis.contains(where: { $0.caseInsensitiveCompare(imei) == .orderedSame }) {
            imeiCheckState = .alreadyInBatch; return
        }
        guard let repo = invRepo else { addImeiDirectly(imei); return }
        imeiCheckState = .checking
        checkTask?.cancel()
        checkTask = Task {
            do {
                let inStock = try await repo.isImeiInStock(imei: imei)
                if inStock.boolValue { self.imeiCheckState = .alreadyInStock }
                else { self.addImeiDirectly(imei) }
            } catch { self.addImeiDirectly(imei) }
        }
    }

    private func addImeiDirectly(_ imei: String) {
        stagedImeis.append(imei)
        pendingImei = ""
        imeiCheckState = .idle
    }

    func removeImei(_ imei: String) { stagedImeis.removeAll { $0 == imei } }

    func proceedToReview() {
        guard let location = batchLocation else { return }
        reviewUnits = stagedImeis.map {
            ReviewUnit(imei: $0, cost: batchCost, condition: batchCondition, location: location,
                       attributes: picked, sellingPrice: defaultSellingPrice)
        }
        route = .review
    }

    func addMoreFromReview() { route = .entry }

    // MARK: - SICKW paste (#53)

    /// Navigate to the paste screen (data already staged is kept; the paste box persists).
    func goToPaste() { pasteError = nil; route = .paste }

    /// Parse the pasted SICKW text, resolve names → vocabulary AttributeRefs, build review
    /// rows (cost/condition/location start empty), stash unreadable blocks, then go to review.
    /// Never auto-runs — only the explicit "Parse & add" action calls this.
    func parseAndAdd() {
        guard let session = session, let repo = attrRepo else { return }
        let text = pasteText
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        parsing = true
        pasteError = nil
        Task {
            let result = parseSickw(text: text)
            do {
                let resolved = try await ResolveParsedPhonesUseCase(attributeRepository: repo)
                    .execute(session: session, parsed: result.phones, known: self.attributes)

                let newRows: [ReviewUnit] = resolved.phones.map { phone in
                    var attrs: [AttributeType: AttributeRef] = [:]
                    for (type, ref) in phone.attributes { attrs[type] = ref }
                    return ReviewUnit(
                        imei: phone.imei,
                        cost: "",
                        condition: .theNew,
                        location: AttributeRef(attributeId: "", name: ""),
                        attributes: attrs,
                        sellingPrice: phone.sellingPrice,
                        fromSickw: true
                    )
                }

                let blocks = result.unreadable.map {
                    UnreadableItem(rawText: $0.rawText, reason: $0.reason)
                }

                self.parsing = false

                if newRows.isEmpty && !blocks.isEmpty {
                    // Nothing usable parsed — keep the user on the paste screen with a message.
                    self.unreadable = blocks
                    self.pasteError = "none"
                    return
                }
                if newRows.isEmpty {
                    self.pasteError = "none"
                    return
                }

                // Merge: keep any previously-staged/reviewed rows, append the new ones.
                self.reviewUnits.append(contentsOf: newRows)
                self.newlyCreatedIds.formUnion(resolved.newlyCreatedIds.map { $0 })
                self.unreadable.append(contentsOf: blocks)
                self.parseSummary = self.buildSummary(parsed: newRows.count, unreadable: blocks.count)
                self.pasteText = ""
                self.route = .review
                self.refreshAllRowImeiStatus()
            } catch {
                self.parsing = false
                self.pasteError = "network:\(self.describe(error))"
            }
        }
    }

    private func buildSummary(parsed: Int, unreadable: Int) -> String {
        // Encodes counts; the view resolves the localized string (parity with error encoding).
        "\(parsed)|\(unreadable)"
    }

    func dismissParseSummary() { parseSummary = nil }

    func dismissUnreadable(_ item: UnreadableItem) {
        unreadable.removeAll { $0.id == item.id }
    }

    func dismissAllUnreadable() { unreadable.removeAll() }

    // MARK: - Apply-to-all + inline review edits (#53)

    /// Fill selling price / cost / condition / location on every review row at once
    /// (per-row override is preserved because the user can still edit any cell afterward).
    func applyToAll(sellingPrice: String?, cost: String?, condition: Condition?, location: AttributeRef?) {
        reviewUnits = reviewUnits.map { unit in
            var u = unit
            if let sellingPrice = sellingPrice { u.sellingPrice = sellingPrice }
            if let cost = cost { u.cost = cost }
            if let condition = condition { u.condition = condition }
            if let location = location { u.location = location }
            return u
        }
    }

    /// Inline cell edits from the review row (no dialog).
    func setRowCost(_ id: UUID, _ value: String) { mutateRow(id) { $0.cost = value } }
    func setRowSellingPrice(_ id: UUID, _ value: String) { mutateRow(id) { $0.sellingPrice = value } }
    func setRowCondition(_ id: UUID, _ value: Condition) { mutateRow(id) { $0.condition = value } }
    func setRowLocation(_ id: UUID, _ value: AttributeRef?) {
        mutateRow(id) { $0.location = value ?? AttributeRef(attributeId: "", name: "") }
    }

    private func mutateRow(_ id: UUID, _ transform: (inout ReviewUnit) -> Void) {
        guard let idx = reviewUnits.firstIndex(where: { $0.id == id }) else { return }
        var unit = reviewUnits[idx]
        transform(&unit)
        reviewUnits[idx] = unit
    }

    /// True when a row is still missing a shop field or a SKU-defining attribute (must-fill).
    ///
    /// Selling price is required because the shared `AddStockUseCase` rejects a new SKU
    /// with a non-positive `defaultSellingPrice`; SICKW carries no price, so a parsed row
    /// stays must-fill until the user supplies one (via Apply-to-all or the inline cell).
    func rowNeedsDetails(_ unit: ReviewUnit) -> Bool {
        if unit.cost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        if unit.sellingPrice.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        if unit.location.attributeId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        // Every SKU-defining attribute the flow expects must be present.
        for type in AttributeType.companion.SKU_DEFINING where unit.attributes[type] == nil {
            return true
        }
        return false
    }

    /// Confirm is allowed only when every row is complete (cost / selling price / location
    /// / all SKU attributes) and none has a blocking IMEI problem. This mirrors the shared
    /// `AddStockUseCase` preconditions so Confirm never throws at runtime for a paste batch.
    var canConfirm: Bool {
        !reviewUnits.isEmpty &&
        reviewUnits.allSatisfy { !rowNeedsDetails($0) && !rowHasProblem($0) }
    }

    func rowHasProblem(_ unit: ReviewUnit) -> Bool {
        let status = rowImeiStatus[unit.id]
        return status == .dupInBatch || status == .inStock
    }

    func status(for unit: ReviewUnit) -> RowImeiStatus { rowImeiStatus[unit.id] ?? .ok }

    /// Re-run the in-batch dedup + already-in-stock check for every row (called after a
    /// parse, an inline IMEI change, or a row removal).
    func refreshAllRowImeiStatus() {
        checkTask?.cancel()
        // In-batch duplicates first (synchronous, exact + case-insensitive).
        var seen: [String: UUID] = [:]
        var dup: Set<UUID> = []
        for unit in reviewUnits {
            let key = unit.imei.lowercased()
            if seen[key] != nil { dup.insert(unit.id) } else { seen[key] = unit.id }
        }
        var next: [UUID: RowImeiStatus] = [:]
        for unit in reviewUnits {
            next[unit.id] = dup.contains(unit.id) ? .dupInBatch : (rowImeiStatus[unit.id] == .inStock ? .inStock : .checking)
        }
        rowImeiStatus = next

        guard let repo = invRepo else {
            for unit in reviewUnits where rowImeiStatus[unit.id] == .checking {
                rowImeiStatus[unit.id] = .ok
            }
            return
        }
        let units = reviewUnits
        checkTask = Task {
            for unit in units {
                if Task.isCancelled { return }
                if dup.contains(unit.id) { continue }
                do {
                    let inStock = try await repo.isImeiInStock(imei: unit.imei)
                    if self.rowImeiStatus[unit.id] == .dupInBatch { continue }
                    self.rowImeiStatus[unit.id] = inStock.boolValue ? .inStock : .ok
                } catch {
                    self.rowImeiStatus[unit.id] = .ok
                }
            }
        }
    }

    func setPickedFromUnit(attributes: [AttributeType: AttributeRef], sellingPrice: String) {
        picked = attributes
        defaultSellingPrice = sellingPrice
    }

    func checkImeiAvailability(imei: String, existingImeis: Set<String>) async -> ImeiCheckState {
        guard Self.isValidImei(imei) else { return .invalid }
        if existingImeis.contains(imei) { return .alreadyInBatch }
        guard let repo = invRepo else { return .available }
        do {
            let inStock = try await repo.isImeiInStock(imei: imei)
            return inStock.boolValue ? .alreadyInStock : .available
        } catch {
            return .available
        }
    }

    func updateReviewUnit(_ unit: ReviewUnit) {
        if let idx = reviewUnits.firstIndex(where: { $0.id == unit.id }) {
            reviewUnits[idx] = unit
            refreshAllRowImeiStatus()
        }
    }

    func removeReviewUnit(_ unit: ReviewUnit) {
        reviewUnits.removeAll { $0.id == unit.id }
        rowImeiStatus[unit.id] = nil
        refreshAllRowImeiStatus()
    }

    func startAddUnits(productId: String, attributes: [AttributeType: AttributeRef], sellingPrice: String = "") {
        picked = attributes; existingProductId = productId; attributesLocked = true
        defaultSellingPrice = sellingPrice
    }

    // MARK: - Purchase dialog (#58)

    /// Batch total = Σ each reviewed unit's cost, as a decimal string (no float — shared Money).
    var batchTotalCost: String { Money.shared.sum(values: reviewUnits.map { $0.cost }) }

    /// True when cash + bank exceeds the batch total — blocks confirm with an inline error.
    var purchasePaidExceedsTotal: Bool {
        let cash = purchaseCash.trimmingCharacters(in: .whitespacesAndNewlines)
        let bank = purchaseBank.trimmingCharacters(in: .whitespacesAndNewlines)
        let paid = Money.shared.add(a: cash.isEmpty ? "0" : cash, b: bank.isEmpty ? "0" : bank)
        return !Money.shared.lessThanOrEqual(a: paid, b: batchTotalCost)
    }

    /// Company parties as dropdown items — suppliers first, always incl. Unspecified Supplier.
    func purchasePartyOptions() -> [AttributeValue] {
        let sorted = entities.filter { $0.isActive }.sorted { a, b in
            let aSup = a.roles.contains(EntityRole.supplier)
            let bSup = b.roles.contains(EntityRole.supplier)
            if aSup != bSup { return aSup }
            return a.name.lowercased() < b.name.lowercased()
        }
        var options = sorted.map {
            AttributeValue(attributeId: $0.id, type: .brand, name: $0.name, nameKey: "", parentId: nil, isActive: true)
        }
        // Always offer the reserved default; the CF materializes the real party lazily.
        if !entities.contains(where: { $0.id == unspecifiedSupplierId }) {
            options.insert(
                AttributeValue(attributeId: unspecifiedSupplierId, type: .brand, name: unspecifiedSupplierName, nameKey: "", parentId: nil, isActive: true),
                at: 0
            )
        }
        return options
    }

    /// Add-new-inline is offered only when the user can manage profiles (it creates a party).
    func canAddSupplierInline() -> Bool { session?.permissions.profiles == PermissionLevel.manage }

    /// Open the purchase dialog. The party is left unselected so the dropdown shows the full
    /// party list (a pre-selected value would pre-fill the search box and filter the list down
    /// to just that one row). "Unspecified Supplier" is the placeholder + the default applied
    /// on confirm when nothing is picked — the "skip" case.
    func openPurchaseDialog() {
        // Batch-size cap: block before opening and ask the user to split (parity with Desktop).
        if exceedsBatchCap { error = "batchCap"; return }
        purchaseParty = nil
        purchaseCash = ""
        purchaseBank = ""
        // Compute the commission the active rules propose for this batch (ticket #97). Grouped by
        // location inside the calculator, so a batch spanning two shops gets the right lines for
        // each. Seed one editable decision per line (default: accrue, at the rule's figure).
        let units = reviewUnits.map {
            NewUnit(imei: $0.imei, cost: $0.cost, condition: $0.condition, location: $0.location)
        }
        let lines = CommissionCalculator.shared.compute(units: units, activeRules: activeCommissionRules)
        commissionLines = lines
        var decisions: [String: CommissionDecisionState] = [:]
        for line in lines { decisions[line.ruleId] = CommissionDecisionState(amount: line.amount) }
        commissionDecisions = decisions
        showPurchaseDialog = true
    }

    func setPurchaseParty(_ value: AttributeValue) {
        purchaseParty = AttributeRef(attributeId: value.attributeId, name: value.name)
    }

    // MARK: - Commission decisions (#97)

    func setCommissionIncluded(_ ruleId: String, _ included: Bool) {
        guard var d = commissionDecisions[ruleId] else { return }
        d.included = included
        commissionDecisions[ruleId] = d
    }

    func setCommissionGiveNow(_ ruleId: String, _ giveNow: Bool) {
        guard var d = commissionDecisions[ruleId] else { return }
        d.giveNow = giveNow
        commissionDecisions[ruleId] = d
    }

    func setCommissionCash(_ ruleId: String, _ raw: String) {
        guard var d = commissionDecisions[ruleId] else { return }
        d.cash = Self.sanitizeDecimalInput(raw)
        commissionDecisions[ruleId] = d
    }

    func setCommissionBank(_ ruleId: String, _ raw: String) {
        guard var d = commissionDecisions[ruleId] else { return }
        d.bank = Self.sanitizeDecimalInput(raw)
        commissionDecisions[ruleId] = d
    }

    /// True when any included commission's give-now split exceeds what's owed — blocks confirm.
    var commissionGiveExceeds: Bool {
        commissionLines.contains { line in
            if let d = commissionDecisions[line.ruleId] { return d.included && d.giveExceedsAmount }
            return false
        }
    }

    /// Hand-edit a line's amount for this batch. Digits + one decimal point only (never a float).
    /// Marked `overridden` when it no longer equals the rule's figure — so a reviewer can tell it
    /// wasn't the rule, and the persisted commission drops its ruleId.
    func setCommissionAmount(_ ruleId: String, _ raw: String) {
        guard var d = commissionDecisions[ruleId] else { return }
        let cleaned = Self.sanitizeDecimalInput(raw)
        let line = commissionLines.first { $0.ruleId == ruleId }
        d.amount = cleaned
        d.overridden = (line == nil) || cleaned != line!.amount
        commissionDecisions[ruleId] = d
    }

    /// Display name of a commission payee (from the loaded entity list); falls back to the id.
    func payeeName(_ entityId: String) -> String {
        entities.first { $0.id == entityId }?.name ?? entityId
    }

    /// Display name of a location attribute (from the loaded vocabulary); falls back to the id.
    func locationName(_ attributeId: String) -> String {
        attributes.first { $0.attributeId == attributeId }?.name ?? attributeId
    }

    /// Turn the user's kept-and-decided commission lines into `CommissionInput`s (ticket #97).
    /// Skipped lines drop; a hand-edited line drops its ruleId; a non-positive amount is skipped.
    private func buildCommissionInputs() -> [CommissionInput] {
        commissionLines.compactMap { line -> CommissionInput? in
            guard let d = commissionDecisions[line.ruleId], d.included else { return nil }
            let amount = d.amount.trimmingCharacters(in: .whitespacesAndNewlines)
            guard Money.shared.isValidPositiveDecimal(value: amount) else { return nil }
            return CommissionInput(
                payeeEntityId: line.payeeEntityId,
                locationAttributeId: line.locationAttributeId,
                ruleId: d.overridden ? nil : line.ruleId,
                unitCount: line.unitCount,
                basisAmount: line.basisAmount,
                amount: amount,
                paidCash: d.giveNow ? (d.cash.isEmpty ? "0" : d.cash) : "0",
                paidBank: d.giveNow ? (d.bank.isEmpty ? "0" : d.bank) : "0"
            )
        }
    }

    /// Keeps only digits and at most one decimal point — the numeric-only money input the ticket
    /// calls for. Money stays a decimal string; there is never a float.
    static func sanitizeDecimalInput(_ raw: String) -> String {
        let kept = raw.filter { $0.isNumber || $0 == "." }
        guard let firstDot = kept.firstIndex(of: ".") else { return kept }
        let head = kept[..<kept.index(after: firstDot)]
        let tail = kept[kept.index(after: firstDot)...].replacingOccurrences(of: ".", with: "")
        return String(head) + tail
    }

    /// Add-new-inline: create a name-only SUPPLIER party and select it.
    func addNewSupplier(name: String) {
        guard let session = session, let repo = entityRepo else { return }
        Task {
            do {
                let id = try await AddSupplierInlineUseCase(repository: repo).execute(session: session, name: name)
                self.purchaseParty = AttributeRef(attributeId: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines))
            } catch {
                self.error = "network:\(self.describe(error))"
            }
        }
    }

    /// Confirm the dialog: record against the chosen party + entered amounts, then save.
    func confirmPurchaseAndSave() {
        if purchasePaidExceedsTotal || commissionGiveExceeds { return }
        let partyId = purchaseParty?.attributeId.isEmpty == false ? purchaseParty!.attributeId : unspecifiedSupplierId
        saveInventoryWithPurchase(
            partyId: partyId,
            cash: purchaseCash.trimmingCharacters(in: .whitespacesAndNewlines),
            bank: purchaseBank.trimmingCharacters(in: .whitespacesAndNewlines),
            commissions: buildCommissionInputs()
        )
    }

    /// Cancel the dialog (tap-away / close): just close it and save nothing. The batch stays
    /// on the review screen so the user can reopen and Confirm. Only the Confirm button
    /// commits the inventory + purchase.
    func dismissPurchaseDialog() {
        showPurchaseDialog = false
    }

    func save() { openPurchaseDialog() }

    /// Saves the batch: its stock **and** its purchase record land in **one Firestore
    /// transaction** (either both commit or neither) — so a failed purchase write can never
    /// leave phantom inventory the books never learn about (ticket #58). A real failure is
    /// surfaced to the cashier, never swallowed.
    private func saveInventoryWithPurchase(partyId: String, cash: String, bank: String, commissions: [CommissionInput]) {
        guard let session = session, let repo = invRepo else { return }
        // Batch-size cap: block the atomic write and ask the user to split (no chunking).
        if exceedsBatchCap { showPurchaseDialog = false; error = "batchCap"; return }
        showPurchaseDialog = false
        saving = true; error = nil
        let groups = buildStockGroups()
        Task {
            do {
                // Business date (ticket #107): today on a phone — no picker here yet. SKIE
                // surfaces no Kotlin defaults, so both must be passed explicitly.
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                try await RecordInventoryPurchaseUseCase(repository: repo)
                    .execute(session: session, groups: groups, partyEntityId: partyId, cashPaid: cash,
                             bankPaid: bank, commissions: commissions, purchaseDate: nowMs, now: nowMs)
                self.saving = false; self.done = true
            } catch {
                self.saving = false
                self.error = self.isDuplicateImei(error) ? "duplicate" : "network:\(self.describe(error))"
            }
        }
    }

    /// Splits the reviewed batch into per-SKU write groups, preserving first-appearance
    /// order. The single add-units-to-an-existing-SKU path carries a nil product (the
    /// product doc already exists); every other group carries a `NewProduct` to
    /// find-or-create, keyed by the canonical shared `SkuKey` (the product doc id).
    private func buildStockGroups() -> [StockBatchGroup] {
        var order: [String] = []
        var groupMap: [String: [ReviewUnit]] = [:]
        for unit in reviewUnits {
            let key = unit.skuKey
            if groupMap[key] == nil { order.append(key) }
            groupMap[key, default: []].append(unit)
        }
        let existing = existingProductId
        return order.compactMap { key -> StockBatchGroup? in
            guard let group = groupMap[key] else { return nil }
            let newUnits = group.map {
                NewUnit(imei: $0.imei, cost: $0.cost, condition: $0.condition, location: $0.location)
            }
            if let existing = existing, order.count == 1 {
                return StockBatchGroup(skuKey: existing, product: nil, units: newUnits)
            }
            let rep = group.first!
            let product = NewProduct(
                attributes: rep.attributes,
                defaultSellingPrice: rep.sellingPrice.trimmingCharacters(in: .whitespacesAndNewlines),
                trackingMode: .serialized
            )
            return StockBatchGroup(
                skuKey: SkuKey.shared.build(attributes: rep.attributes),
                product: product,
                units: newUnits
            )
        }
    }

    func dismissError() { error = nil }

    func reset() {
        picked = [:]; defaultSellingPrice = ""; batchCost = ""; batchCondition = .theNew
        batchLocation = nil; pendingImei = ""; imeiCheckState = .idle; stagedImeis = []
        route = .entry; reviewUnits = []; existingProductId = nil; attributesLocked = false
        saving = false; error = nil; done = false
        pasteText = ""; parsing = false; pasteError = nil; unreadable = []
        newlyCreatedIds = []; parseSummary = nil; rowImeiStatus = [:]
        commissionLines = []; commissionDecisions = [:]
        // activeCommissionRules is left intact — it comes from the live stream, not the form.
    }

    // MARK: - Validation

    /// 14–16 digits (matches shared Imei.isValid). Not a Luhn check (v1 scope).
    private static func isValidImei(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 14, trimmed.count <= 16 else { return false }
        return trimmed.allSatisfy { $0.isNumber }
    }

    // MARK: - Duplicate detection

    private func isDuplicateImei(_ error: Error) -> Bool {
        let ns = error as NSError
        if ns.domain == BackendInventoryRepository.duplicateDomain { return true }
        if (ns.kotlinException as? KotlinThrowable)?.message?.contains(BackendInventoryRepository.duplicateMarker) == true { return true }
        if ns.localizedDescription.contains(BackendInventoryRepository.duplicateMarker) { return true }
        if let underlying = ns.userInfo[NSUnderlyingErrorKey] as? NSError,
           underlying.domain == BackendInventoryRepository.duplicateDomain { return true }
        return false
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.kotlinException as? KotlinThrowable { return throwable.message ?? "Save failed" }
        return ns.localizedDescription
    }
}
