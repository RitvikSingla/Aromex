import Foundation
import FirebaseFirestore
import SharedLogic

/// Firestore-backed implementation of the shared `CommissionRuleRepository` (ticket #97).
///
/// ── Flow-interop (SKIE) ──────────────────────────────────────────────────────
/// `observeRules(includeInactive:)` returns a Kotlin `Flow<List<CommissionRule>>`, built with
/// the shared `commissionRulesCallbackFlow(subscribe:)` adapter feeding a Firestore snapshot
/// listener — so the shared use cases (admin gate for management, inventory gate for intake)
/// stay the single source of the list rules. SKIE `__`-prefixes the suspend members.
///
/// Firebase-only: a commission rule touches no Humble Ledger; it only decides what a later
/// intake will owe. Writes are reached only through the admin-gated shared use cases.
final class BackendCommissionRuleRepository: NSObject, CommissionRuleRepository {

    private let uid: String
    private let collection: CollectionReference

    init(config: FirebaseClientConfig, uid: String) {
        self.uid = uid
        self.collection = FirebaseAppFactory.firestore(for: config).collection("commissionRules")
        super.init()
    }

    // MARK: - Live read (produces the shared Flow the use case consumes)

    func observeRules(includeInactive: Bool) -> SkieSwiftFlow<[CommissionRule]> {
        return commissionRulesCallbackFlow { onEach, onError in
            let query: Query = includeInactive
                ? self.collection
                : self.collection.whereField("isActive", isEqualTo: true)
            let registration = query.addSnapshotListener { snapshot, error in
                if let error = error {
                    _ = onError(error.localizedDescription)
                    return
                }
                let rules = snapshot?.documents.compactMap {
                    Self.mapDoc(id: $0.documentID, data: $0.data())
                } ?? []
                _ = onEach(rules)
            }
            return FirestoreCommissionRuleObservation(registration)
        }
    }

    // MARK: - Suspend writes (consumed by the admin-gated shared use cases)

    func __saveRule(input: CommissionRuleInput) async throws -> String {
        let editing = !input.ruleId.isEmpty
        let ref = editing ? collection.document(input.ruleId) : collection.document()
        // Editing keeps the same doc so earned commissions keep pointing at a live rule; a
        // create seeds createdBy/createdAt, an edit only bumps updatedAt.
        var data: [String: Any] = [
            "ruleId": ref.documentID,
            "locationAttributeId": input.locationAttributeId,
            "payeeEntityId": input.payeeEntityId,
            "rateKind": input.rateKind.name,
            "rate": input.rate,            // decimal String — never a Double
            "isActive": input.isActive,
            "updatedAt": FieldValue.serverTimestamp(),
        ]
        if !editing {
            data["createdBy"] = uid
            data["createdAt"] = FieldValue.serverTimestamp()
        }
        try await ref.setData(data, merge: true)
        return ref.documentID
    }

    func __archiveRule(ruleId: String) async throws {
        try await collection.document(ruleId).updateData([
            "isActive": false,
            "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    func close() {}

    // MARK: - Mapping

    static func mapDoc(id: String, data: [String: Any]) -> CommissionRule? {
        guard let rateKind = RateKind.companion.fromWire(value: (data["rateKind"] as? String) ?? "") else {
            return nil
        }
        return CommissionRule(
            ruleId: (data["ruleId"] as? String) ?? id,
            locationAttributeId: (data["locationAttributeId"] as? String) ?? "",
            payeeEntityId: (data["payeeEntityId"] as? String) ?? "",
            rateKind: rateKind,
            rate: (data["rate"] as? String) ?? "0",
            isActive: (data["isActive"] as? Bool) ?? true,
            createdBy: data["createdBy"] as? String,
            createdAt: nil,
            updatedAt: nil
        )
    }
}

/// Detaches the Firestore snapshot listener when the shared observe `Flow` is cancelled.
private final class FirestoreCommissionRuleObservation: NSObject, CommissionRuleObservation {
    private let registration: ListenerRegistration
    init(_ registration: ListenerRegistration) { self.registration = registration }
    func cancel() { registration.remove() }
}
