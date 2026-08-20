import Foundation
import SharedLogic

/// The commission-rule form's editable fields (ticket #97). `rateInput` is what the admin types:
/// a money amount for PER_UNIT, or a percent number (`2` for 2%) for PERCENT_OF_COST — converted
/// to/from the stored fraction at the VM boundary so the admin never thinks in fractions.
struct CommissionRuleForm {
    var ruleId: String = ""
    var location: AttributeRef? = nil
    var payeeId: String = ""
    var payeeName: String = ""
    var rateKind: RateKind = .perUnit
    var rateInput: String = ""
    var isActive: Bool = true

    var isEditing: Bool { !ruleId.isEmpty }
}

/// Admin-only commission-rules settings VM (ticket #97). `bind` records whether the session is an
/// admin, and the use cases reject a non-admin regardless. Streams the rules (incl. switched-off
/// ones), plus LOCATION vocabulary and the party list for the add/edit form.
@MainActor
final class CommissionRulesViewModel: ObservableObject {
    @Published private(set) var isAdmin: Bool = false
    @Published private(set) var currency: String = ""
    @Published private(set) var rules: [CommissionRule] = []
    @Published private(set) var locations: [AttributeValue] = []
    @Published private(set) var payees: [Entity] = []
    @Published var showDialog: Bool = false
    @Published var form: CommissionRuleForm = CommissionRuleForm()
    @Published private(set) var saving: Bool = false
    @Published var error: String? = nil

    private var session: UserSession? = nil
    private var config: FirebaseClientConfig? = nil
    private var ruleRepo: BackendCommissionRuleRepository? = nil
    private var saveUseCase: SaveCommissionRuleUseCase? = nil
    private var archiveUseCase: ArchiveCommissionRuleUseCase? = nil
    private var addAttributeUseCase: AddAttributeUseCase? = nil
    private var addSupplierInlineUseCase: AddSupplierInlineUseCase? = nil
    private var rulesTask: Task<Void, Never>? = nil
    private var locationsTask: Task<Void, Never>? = nil
    private var payeesTask: Task<Void, Never>? = nil

    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, self.config == config { return }
        self.session = session
        self.config = config
        self.currency = session.currency
        self.isAdmin = session.role == UserRole.admin
        guard isAdmin else { return }

        let rules = BackendCommissionRuleRepository(config: config, uid: session.uid)
        let attrs = BackendAttributeRepository(config: config, uid: session.uid)
        let entities = BackendEntityRepository(config: config, uid: session.uid)
        self.ruleRepo = rules
        self.saveUseCase = SaveCommissionRuleUseCase(repository: rules)
        self.archiveUseCase = ArchiveCommissionRuleUseCase(repository: rules)
        self.addAttributeUseCase = AddAttributeUseCase(repository: attrs)
        self.addSupplierInlineUseCase = AddSupplierInlineUseCase(repository: entities)

        rulesTask?.cancel()
        rulesTask = Task { [weak self] in
            do {
                let flow = try ObserveCommissionRulesUseCase(repository: rules)
                    .execute(session: session, includeInactive: true)
                for try await list in flow { self?.rules = list }
            } catch {
                self?.error = (error as NSError).localizedDescription
            }
        }
        locationsTask?.cancel()
        locationsTask = Task { [weak self] in
            do {
                for try await list in attrs.observeAttributes() {
                    self?.locations = list.filter { $0.type == AttributeType.location && $0.isActive }
                }
            } catch { /* leave locations empty */ }
        }
        payeesTask?.cancel()
        payeesTask = Task { [weak self] in
            do {
                for try await list in entities.observeEntities(includeArchived: false) {
                    self?.payees = list.filter { $0.isActive }
                }
            } catch { /* leave payees empty */ }
        }
    }

    func openAddDialog() {
        error = nil
        form = CommissionRuleForm()
        showDialog = true
    }

    func openEditDialog(_ rule: CommissionRule) {
        error = nil
        let locName = locations.first { $0.attributeId == rule.locationAttributeId }?.name ?? ""
        let payeeName = payees.first { $0.id == rule.payeeEntityId }?.name ?? ""
        form = CommissionRuleForm(
            ruleId: rule.ruleId,
            location: AttributeRef(attributeId: rule.locationAttributeId, name: locName),
            payeeId: rule.payeeEntityId,
            payeeName: payeeName,
            rateKind: rule.rateKind,
            rateInput: rule.rateKind == .perUnit ? rule.rate : Self.fractionToPercent(rule.rate),
            isActive: rule.isActive
        )
        showDialog = true
    }

    func dismissDialog() { showDialog = false }

    func setFormLocation(_ value: AttributeValue) {
        form.location = AttributeRef(attributeId: value.attributeId, name: value.name)
    }

    func setFormPayee(_ entity: Entity) {
        form.payeeId = entity.id
        form.payeeName = entity.name
    }

    func setFormRateKind(_ kind: RateKind) {
        form.rateKind = kind
        form.rateInput = ""
    }

    func setFormRate(_ raw: String) {
        form.rateInput = AddStockViewModel.sanitizeDecimalInput(raw)
    }

    func save() {
        guard let session = session, let useCase = saveUseCase else { return }
        guard let location = form.location, !location.attributeId.isEmpty else {
            error = "A location is required"; return
        }
        guard !form.payeeId.isEmpty else { error = "A payee is required"; return }
        let rate = form.rateKind == .perUnit
            ? form.rateInput.trimmingCharacters(in: .whitespacesAndNewlines)
            : Self.percentToFraction(form.rateInput.trimmingCharacters(in: .whitespacesAndNewlines))
        let input = CommissionRuleInput(
            ruleId: form.ruleId,
            locationAttributeId: location.attributeId,
            payeeEntityId: form.payeeId,
            rateKind: form.rateKind,
            rate: rate,
            isActive: form.isActive
        )
        saving = true; error = nil
        Task {
            do {
                _ = try await useCase.execute(session: session, input: input)
                self.saving = false; self.showDialog = false
            } catch {
                self.saving = false
                self.error = (error as NSError).localizedDescription
            }
        }
    }

    func archive(_ ruleId: String) {
        guard let session = session, let useCase = archiveUseCase else { return }
        Task {
            do { try await useCase.execute(session: session, ruleId: ruleId) }
            catch { self.error = (error as NSError).localizedDescription }
        }
    }

    /// Flip a rule on/off from the row's switch. Off reuses the archive path; on re-saves the same
    /// doc with isActive = true. Neither touches commission already earned.
    func setRuleActive(_ rule: CommissionRule, _ active: Bool) {
        guard let session = session else { return }
        if !active { archive(rule.ruleId); return }
        guard let useCase = saveUseCase else { return }
        let input = CommissionRuleInput(
            ruleId: rule.ruleId,
            locationAttributeId: rule.locationAttributeId,
            payeeEntityId: rule.payeeEntityId,
            rateKind: rule.rateKind,
            rate: rule.rate,
            isActive: true
        )
        Task {
            do { _ = try await useCase.execute(session: session, input: input) }
            catch { self.error = (error as NSError).localizedDescription }
        }
    }

    /// Add-new-inline from the rule dialog's Location dropdown: mint a LOCATION value and select it.
    func addLocationInline(_ name: String) {
        guard let session = session, let useCase = addAttributeUseCase else { return }
        Task {
            do {
                let id = try await useCase.execute(session: session, type: AttributeType.location, name: name, parentId: nil)
                self.form.location = AttributeRef(attributeId: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines))
            } catch { self.error = (error as NSError).localizedDescription }
        }
    }

    /// Add-new-inline from the rule dialog's Payee dropdown: create a name-only party and select it.
    func addPayeeInline(_ name: String) {
        guard let session = session, let useCase = addSupplierInlineUseCase else { return }
        Task {
            do {
                let id = try await useCase.execute(session: session, name: name)
                self.form.payeeId = id
                self.form.payeeName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            } catch { self.error = (error as NSError).localizedDescription }
        }
    }

    func dismissError() { error = nil }

    // MARK: - Percent ↔ fraction (pure decimal-shift string maths; never the 2-dp rounding of
    // Money.multiplyRate, which would corrupt a fractional-percent rate — ticket #97).

    /// Percent number → stored fraction (shift two places left): "2" → "0.02", "2.5" → "0.025".
    static func percentToFraction(_ percent: String) -> String {
        let t = percent.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return "" }
        let parts = t.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        let intPart = String(parts[0])
        let fracPart = parts.count > 1 ? String(parts[1]) : ""
        var digits = intPart + fracPart
        if digits.isEmpty { digits = "0" }
        let newFracLen = fracPart.count + 2
        let padded = String(repeating: "0", count: max(0, newFracLen + 1 - digits.count)) + digits
        let cut = padded.count - newFracLen
        let wholeRaw = String(padded.prefix(cut))
        let fracRaw = String(padded.suffix(newFracLen))
        let whole = trimLeadingZeros(wholeRaw)
        let frac = trimTrailingZeros(fracRaw)
        return frac.isEmpty ? whole : "\(whole).\(frac)"
    }

    /// Stored fraction → percent number (shift two places right): "0.02" → "2". Inverse of above.
    static func fractionToPercent(_ fraction: String) -> String {
        let t = fraction.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return "" }
        let parts = t.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        let intPart = String(parts[0])
        let fracPart = parts.count > 1 ? String(parts[1]) : ""
        let digits = intPart + fracPart
        let fracLen = fracPart.count
        if fracLen <= 2 {
            return trimLeadingZeros(digits + String(repeating: "0", count: 2 - fracLen))
        } else {
            let newFracLen = fracLen - 2
            let whole = trimLeadingZeros(String(digits.prefix(digits.count - newFracLen)))
            let frac = trimTrailingZeros(String(digits.suffix(newFracLen)))
            return frac.isEmpty ? whole : "\(whole).\(frac)"
        }
    }

    private static func trimLeadingZeros(_ s: String) -> String {
        let t = String(s.drop { $0 == "0" })
        return t.isEmpty ? "0" : t
    }

    private static func trimTrailingZeros(_ s: String) -> String {
        var t = s
        while t.hasSuffix("0") { t.removeLast() }
        return t
    }
}
