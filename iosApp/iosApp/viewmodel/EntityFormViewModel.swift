import Foundation
import SharedLogic

/// Add/Edit form state. Builds an `EntityInput` and saves it via the shared
/// `SaveEntityUseCase` (which enforces the `profiles` MANAGE scope + validates).
@MainActor
final class EntityFormViewModel: ObservableObject {
    enum OpeningDirection: CaseIterable, Identifiable {
        case receivable, credit
        var id: Self { self }
        var label: String {
            switch self {
            case .receivable: return "They owe us (receivable)"
            case .credit: return "We owe them (credit)"
            }
        }
        var direction: BalanceDirection {
            switch self {
            case .receivable: return BalanceDirection.receivable
            case .credit: return BalanceDirection.credit
            }
        }
    }

    // Editable fields.
    @Published var name: String = ""
    @Published var phonesText: String = ""      // one per line
    @Published var email: String = ""
    @Published var address: String = ""
    @Published var notes: String = ""
    /// Optional tax/GST number (ticket #106) — printed on this party's invoices when they buy.
    @Published var taxNumber: String = ""
    @Published var isCustomer: Bool = false
    @Published var isSupplier: Bool = false
    @Published var isMiddleman: Bool = false
    // Opening balance — create-only.
    @Published var openingAmount: String = ""
    @Published var openingDirection: OpeningDirection = .receivable

    @Published private(set) var isSaving: Bool = false
    @Published var errorMessage: String? = nil
    @Published private(set) var saved: Bool = false

    private let session: UserSession
    private let saveUseCase: SaveEntityUseCase
    private let existing: Entity?

    /// - Parameter existing: nil → create; non-nil → edit that entity.
    init(session: UserSession, config: FirebaseClientConfig, existing: Entity?) {
        self.session = session
        // Writes-only repo — no listener, no ledger, nothing to close.
        let repo = BackendEntityRepository(config: config, uid: session.uid)
        self.saveUseCase = SaveEntityUseCase(repository: repo)
        self.existing = existing
        if let e = existing {
            name = e.name
            phonesText = e.phones.joined(separator: "\n")
            email = e.email ?? ""
            address = e.address ?? ""
            notes = e.notes ?? ""
            taxNumber = e.taxNumber ?? ""
            isCustomer = e.roles.contains(EntityRole.customer)
            isSupplier = e.roles.contains(EntityRole.supplier)
            isMiddleman = e.roles.contains(EntityRole.middleman)
        }
    }

    var isEditing: Bool { existing != nil }
    var isWalkIn: Bool { existing?.isWalkIn == true }
    /// Walk-in cannot be renamed; opening balance is only offered on create.
    var showsOpening: Bool { !isEditing }

    func save() {
        if isSaving { return }
        let phones = phonesText
            .split(whereSeparator: { $0 == "\n" || $0 == "," })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        var roles = Set<EntityRole>()
        if isCustomer { roles.insert(EntityRole.customer) }
        if isSupplier { roles.insert(EntityRole.supplier) }
        if isMiddleman { roles.insert(EntityRole.middleman) }

        var opening: OpeningBalance? = nil
        let amount = openingAmount.trimmingCharacters(in: .whitespaces)
        if showsOpening, !amount.isEmpty {
            opening = OpeningBalance(amount: amount, direction: openingDirection.direction)
        }

        let input = EntityInput(
            name: name.trimmingCharacters(in: .whitespaces),
            phones: phones,
            email: nilIfBlank(email),
            address: nilIfBlank(address),
            roles: roles,
            notes: nilIfBlank(notes),
            taxNumber: nilIfBlank(taxNumber),
            opening: opening
        )

        isSaving = true
        errorMessage = nil
        Task {
            do {
                _ = try await saveUseCase.execute(session: session, input: input, existingId: existing?.id)
                isSaving = false
                saved = true
            } catch {
                isSaving = false
                errorMessage = describe(error)
            }
        }
    }

    private func nilIfBlank(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.userInfo["KotlinException"] as? KotlinThrowable {
            return throwable.message ?? "Something went wrong"
        }
        return ns.localizedDescription
    }
}
