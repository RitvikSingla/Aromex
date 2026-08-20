import Foundation
import FirebaseFirestore
import SharedLogic

/// Firestore-backed `AttributeRepository` — the `attributes/{attributeId}` managed
/// vocabularies (brand/model/capacity/color/carrier/location). Firebase-only. Mirrors the
/// Android `BackendAttributeRepository`.
///
/// `addAttribute` is the **add-new-inline** path: it dedupes case-insensitively on
/// `(type, parentId, nameKey)` — `nameKey` computed by the shared `AttributeName.matchKey`
/// so every platform folds identically — returning an existing id or creating a new value.
final class BackendAttributeRepository: NSObject, AttributeRepository {

    private let uid: String
    private let collection: CollectionReference

    init(config: FirebaseClientConfig, uid: String) {
        self.uid = uid
        self.collection = FirebaseAppFactory.firestore(for: config).collection("attributes")
        super.init()
    }

    func observeAttributes() -> SkieSwiftFlow<[AttributeValue]> {
        return attributesCallbackFlow { onEach, onError in
            let registration = self.collection
                .whereField("isActive", isEqualTo: true)
                .addSnapshotListener { snapshot, error in
                    if let error = error {
                        _ = onError(error.localizedDescription)
                        return
                    }
                    let items = snapshot?.documents.compactMap { Self.mapAttribute(id: $0.documentID, data: $0.data()) } ?? []
                    _ = onEach(items)
                }
            return FirestoreInventoryObservation(registration)
        }
    }

    func __addAttribute(type: AttributeType, name: String, parentId: String?) async throws -> String {
        let nameKey = AttributeName.shared.matchKey(raw: name)
        // Backward-compat: query first to find existing docs with legacy UUID ids.
        var query: Query = collection
            .whereField("type", isEqualTo: type.wire)
            .whereField("nameKey", isEqualTo: nameKey)
        if let parentId = parentId {
            query = query.whereField("parentId", isEqualTo: parentId)
        } else {
            query = query.whereField("parentId", isEqualTo: NSNull())
        }
        let existing = try await query.limit(to: 1).getDocuments().documents.first
        if let existing = existing {
            return existing.documentID
        }

        // Deterministic document ID: (type, parentId, nameKey) tuple → no duplicate creates under concurrency.
        let deterministicId = "\(type.wire)_\(parentId ?? "")_\(nameKey)"
        let ref = collection.document(deterministicId)
        let db = collection.firestore

        _ = try await db.runTransaction { transaction, errorPointer in
            let snap: DocumentSnapshot
            do {
                snap = try transaction.getDocument(ref)
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
            if !snap.exists {
                transaction.setData([
                    "attributeId": deterministicId,
                    "type": type.wire,
                    "name": name,
                    "nameKey": nameKey,
                    "parentId": parentId as Any? ?? NSNull(),
                    "isActive": true,
                    "createdBy": self.uid,
                    "createdAt": FieldValue.serverTimestamp(),
                ], forDocument: ref)
            }
            return nil
        }
        return deterministicId
    }

    static func mapAttribute(id: String, data: [String: Any]) -> AttributeValue? {
        guard let type = AttributeType.companion.fromWire(value: (data["type"] as? String) ?? "") else { return nil }
        return AttributeValue(
            attributeId: (data["attributeId"] as? String) ?? id,
            type: type,
            name: (data["name"] as? String) ?? "",
            nameKey: (data["nameKey"] as? String) ?? "",
            parentId: data["parentId"] as? String,
            isActive: (data["isActive"] as? Bool) ?? true
        )
    }
}
