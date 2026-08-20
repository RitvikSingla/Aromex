import Foundation
import FirebaseFirestore
import SharedLogic

/// Firestore-backed implementation of the shared `EntityRepository`.
///
/// ── Flow-interop (SKIE) ──────────────────────────────────────────────────────
/// `EntityRepository.observeEntities()` returns a Kotlin `Flow<List<Entity>>`. With
/// SKIE (ticket #34) iOS both *produces* and *consumes* that Flow through shared code:
///   • `observeEntities()` builds a real Kotlin `Flow` via the shared
///     `entitiesCallbackFlow(subscribe:)` adapter, feeding it a Firestore snapshot
///     listener. This is what flips a row PENDING → SYNCED with no manual refresh.
///   • The ViewModel consumes it through the shared `ObserveEntitiesUseCase`, so the
///     `profiles` permission gate + `isActive` filter live once, in shared code.
///
/// Writes only touch Firebase. `createEntity` records the doc as `syncStatus:"PENDING"`;
/// the server-side Cloud Function (T2) performs the HL create and flips it to SYNCED.
/// The client never calls HL to write.
final class BackendEntityRepository: NSObject, EntityRepository {

    private let uid: String
    private let collection: CollectionReference

    init(config: FirebaseClientConfig, uid: String) {
        self.uid = uid
        self.collection = FirebaseAppFactory.firestore(for: config).collection("entities")
        super.init()
    }

    // MARK: - Live read (produces the shared Flow the use case consumes)

    /// A Kotlin `Flow` of the company's entities, driven by a Firestore snapshot
    /// listener. Built with the shared `entitiesCallbackFlow` adapter so it flows
    /// straight into `ObserveEntitiesUseCase` (the single source of the list rules).
    /// Narrows to active-only unless `includeArchived` is set (parity with
    /// Android/Desktop). The listener detaches automatically when the Flow is cancelled.
    func observeEntities(includeArchived: Bool) -> SkieSwiftFlow<[Entity]> {
        return entitiesCallbackFlow { onEach, onError in
            let query: Query = includeArchived
                ? self.collection
                : self.collection.whereField("isActive", isEqualTo: true)
            let registration = query
                .addSnapshotListener { snapshot, error in
                    if let error = error {
                        _ = onError(error.localizedDescription)
                        return
                    }
                    let entities = snapshot?.documents.compactMap {
                        Self.mapDoc(id: $0.documentID, data: $0.data())
                    } ?? []
                    _ = onEach(entities)
                }
            return FirestoreEntityObservation(registration)
        }
    }

    // MARK: - EntityRepository (suspend writes consumed by the shared use cases)

    // SKIE `__`-prefixes suspend interface members for Swift implementors; the shared
    // use cases consume the clean `createEntity`/`updateEntity`/`archiveEntity`. (#34)
    func __createEntity(input: EntityInput) async throws -> String {
        let ref = collection.document()
        var data: [String: Any] = Self.profileFields(from: input)
        // Rules require: clients may only START a party as PENDING, isActive:true,
        // isWalkIn:false, and may NOT include the CF-owned hlCustomerId/hlAccountId.
        data["isWalkIn"] = false
        data["isActive"] = true
        data["syncStatus"] = "PENDING"
        data["createdBy"] = uid
        data["createdAt"] = FieldValue.serverTimestamp()
        data["updatedAt"] = FieldValue.serverTimestamp()
        if let opening = input.opening {
            data["opening"] = [
                "amount": opening.amount,               // decimal String — never a Double
                "direction": opening.direction.name,    // "RECEIVABLE" | "CREDIT"
            ]
        }
        try await ref.setData(data)
        return ref.documentID
    }

    func __updateEntity(id: String, input: EntityInput) async throws {
        // Profile-field edit only. We deliberately DON'T touch syncStatus / the HL
        // fields / opening: the CF's `onEntityWrite` sees the profile change on the
        // already-SYNCED doc and pushes name/email/phone to HL itself. Opening is
        // create-only.
        var data: [String: Any] = Self.profileFields(from: input)
        data["updatedAt"] = FieldValue.serverTimestamp()
        try await collection.document(id).updateData(data)
    }

    func __updateTaxNumber(id: String, taxNumber: String?) async throws {
        // Targeted single-field write for the checkout "Save to contact" action (ticket #106) —
        // touches only taxNumber (+ updatedAt), never the other profile fields. nil clears it.
        try await collection.document(id).updateData([
            "taxNumber": taxNumber as Any? ?? NSNull(),
            "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    func __updatePhones(id: String, phones: [String]) async throws {
        // Sibling of __updateTaxNumber for the checkout "Save to contact" phone action — touches
        // only phones (+ updatedAt). The caller passes the full list to store; empty clears them.
        try await collection.document(id).updateData([
            "phones": phones,
            "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    func __archiveEntity(id: String) async throws {
        // Soft-archive only (rules forbid hard delete). Walk-in is guarded upstream
        // by ArchiveEntityUseCase and by Firestore rules.
        try await collection.document(id).updateData([
            "isActive": false,
            "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    // MARK: - Mapping

    /// The profile fields shared by create + update (everything the user can edit).
    private static func profileFields(from input: EntityInput) -> [String: Any] {
        return [
            "name": input.name,
            "phones": input.phones,                          // [String]
            "email": input.email as Any? ?? NSNull(),
            "address": input.address as Any? ?? NSNull(),
            "roles": rolesToWire(input.roles),               // ["CUSTOMER", ...] UPPERCASE
            "notes": input.notes as Any? ?? NSNull(),
            "taxNumber": input.taxNumber as Any? ?? NSNull(), // ticket #106
        ]
    }

    /// Roles persist as UPPERCASE enum-name strings.
    private static func rolesToWire(_ roles: Set<EntityRole>) -> [String] {
        return roles.map { $0.name }
    }

    /// Firestore document → shared `Entity`. Unknown roles are dropped; unknown/missing
    /// syncStatus defaults to PENDING (safe — it'll be reconciled).
    static func mapDoc(id: String, data: [String: Any]) -> Entity {
        let rawRoles = (data["roles"] as? [String]) ?? []
        var roles = Set<EntityRole>()
        for raw in rawRoles {
            if let role = EntityRole.companion.fromWire(value: raw) {
                roles.insert(role)
            }
        }
        let syncStatus = HlSyncStatus.companion.fromWire(value: (data["syncStatus"] as? String) ?? "PENDING")
        return Entity(
            id: id,
            name: (data["name"] as? String) ?? "",
            phones: (data["phones"] as? [String]) ?? [],
            email: data["email"] as? String,
            address: data["address"] as? String,
            roles: roles,
            notes: data["notes"] as? String,
            taxNumber: data["taxNumber"] as? String,
            isWalkIn: (data["isWalkIn"] as? Bool) ?? false,
            isActive: (data["isActive"] as? Bool) ?? true,
            hlCustomerId: data["hlCustomerId"] as? String,
            hlAccountId: data["hlAccountId"] as? String,
            syncStatus: syncStatus
        )
    }
}

/// Detaches the Firestore snapshot listener when the shared observe `Flow` is
/// cancelled. Conforms to the shared `EntityObservation` handle returned by the
/// `entitiesCallbackFlow` adapter.
private final class FirestoreEntityObservation: NSObject, EntityObservation {
    private let registration: ListenerRegistration
    init(_ registration: ListenerRegistration) { self.registration = registration }
    func cancel() { registration.remove() }
}
