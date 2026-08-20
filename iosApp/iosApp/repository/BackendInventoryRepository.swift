import Foundation
import FirebaseFirestore
import SharedLogic

/// Firestore-backed `InventoryRepository` — the iOS native-SDK impl of the shared
/// contract (Firebase-only; inventory touches no Humble Ledger). Mirrors the Android
/// `BackendInventoryRepository` transaction-for-transaction (docs/SCHEMA.md Part 2).
///
/// ── Flow-interop (SKIE, #34) ─────────────────────────────────────────────────────
/// `observeProducts`/`observeInStockSerials` build real Kotlin `Flow`s via the shared
/// `productsCallbackFlow`/`serialsCallbackFlow` adapters, feeding them Firestore snapshot
/// listeners, so the list flows through the shared `ObserveInventoryUseCase` (the single
/// home of the permission gate + isActive filter).
///
/// ── Duplicate IMEI ───────────────────────────────────────────────────────────────
/// A Kotlin exception cannot be *thrown* from Swift (KotlinThrowable is not a Swift
/// `Error`), so a duplicate in-stock IMEI is signalled with an `NSError` in
/// `Self.duplicateDomain` whose message carries `Self.duplicateMarker`; the ViewModel
/// detects it and shows a field error. The write stays atomic — the transaction aborts
/// before any doc is committed.
final class BackendInventoryRepository: NSObject, InventoryRepository {

    static let duplicateDomain = "com.humblesolutions.aromex.DuplicateImei"
    static let duplicateMarker = "AROMEX_DUP_IMEI"

    private let uid: String
    private let db: Firestore

    init(config: FirebaseClientConfig, uid: String) {
        self.uid = uid
        self.db = FirebaseAppFactory.firestore(for: config)
        super.init()
    }

    private var products: CollectionReference { db.collection("products") }
    private var serials: CollectionReference { db.collection("serials") }
    private var imeiIndex: CollectionReference { db.collection("imeiIndex") }
    private var purchases: CollectionReference { db.collection("purchases") }
    private var commissions: CollectionReference { db.collection("commissions") }

    // MARK: - Live reads (produce the shared Flows the use case consumes)

    func observeProducts(includeArchived: Bool) -> SkieSwiftFlow<[Product]> {
        return productsCallbackFlow { onEach, onError in
            let query: Query = includeArchived
                ? self.products
                : self.products.whereField("isActive", isEqualTo: true)
            let registration = query.addSnapshotListener { snapshot, error in
                if let error = error {
                    _ = onError(error.localizedDescription)
                    return
                }
                let items = snapshot?.documents.compactMap { Self.mapProduct(id: $0.documentID, data: $0.data()) } ?? []
                _ = onEach(items)
            }
            return FirestoreInventoryObservation(registration)
        }
    }

    func observeInStockSerials() -> SkieSwiftFlow<[Serial]> {
        return serialsCallbackFlow { onEach, onError in
            let query = self.serials
                .whereField("status", isEqualTo: SerialStatus.inStock.name)
                .whereField("isActive", isEqualTo: true)
            let registration = query.addSnapshotListener { snapshot, error in
                if let error = error {
                    _ = onError(error.localizedDescription)
                    return
                }
                let items = snapshot?.documents.compactMap { Self.mapSerial(id: $0.documentID, data: $0.data()) } ?? []
                _ = onEach(items)
            }
            return FirestoreInventoryObservation(registration)
        }
    }

    // MARK: - Transactional writes (SKIE `__`-prefixes the suspend interface members)

    func __addStock(skuKey: String, product: NewProduct, units: [NewUnit]) async throws -> AddStockResult {
        let productRef = products.document(skuKey)
        let serialRefs = units.map { _ in serials.document() }
        let imeiRefs = units.map { imeiIndex.document($0.imei.trimmed()) }

        try await db.runTransaction { transaction, errorPointer in
            do {
                let productSnap = try transaction.getDocument(productRef)
                let duplicates = try self.duplicateImeis(units: units, imeiRefs: imeiRefs, transaction: transaction)
                if !duplicates.isEmpty {
                    errorPointer?.pointee = Self.duplicateError(duplicates)
                    return nil
                }
                if !productSnap.exists {
                    transaction.setData(self.productData(skuKey: skuKey, product: product), forDocument: productRef)
                }
                self.writeUnits(units, serialRefs: serialRefs, imeiRefs: imeiRefs, productId: skuKey, transaction: transaction)
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
        return AddStockResult(productId: skuKey, createdSerialIds: serialRefs.map { $0.documentID })
    }

    func __addUnits(productId: String, units: [NewUnit]) async throws -> [String] {
        let serialRefs = units.map { _ in serials.document() }
        let imeiRefs = units.map { imeiIndex.document($0.imei.trimmed()) }

        try await db.runTransaction { transaction, errorPointer in
            do {
                let duplicates = try self.duplicateImeis(units: units, imeiRefs: imeiRefs, transaction: transaction)
                if !duplicates.isEmpty {
                    errorPointer?.pointee = Self.duplicateError(duplicates)
                    return nil
                }
                self.writeUnits(units, serialRefs: serialRefs, imeiRefs: imeiRefs, productId: productId, transaction: transaction)
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
        return serialRefs.map { $0.documentID }
    }

    /// Adds a whole reviewed batch (one or more SKU `groups`) **and** its single `purchase`
    /// record in **one Firestore transaction** — all the stock and the `purchases/{id}` doc
    /// commit together or not at all (ticket #58). This is the invariant: no in-stock unit
    /// can exist without its purchase record (which drives the HL posting), so a failed
    /// purchase write can never leave phantom inventory. The batch is pre-capped
    /// (SAFE_BATCH_CEILING) so it always fits under Firestore's ~500-write limit.
    func __addStockBatchWithPurchase(groups: [StockBatchGroup], purchase: PurchaseInput, commissions: [CommissionInput]) async throws {
        // Pre-allocate every ref outside the txn so the body only reads then writes.
        let prepared: [PreparedGroup] = groups.map { group in
            PreparedGroup(
                group: group,
                productRef: products.document(group.skuKey),
                serialRefs: group.units.map { _ in serials.document() },
                imeiRefs: group.units.map { imeiIndex.document($0.imei.trimmed()) }
            )
        }
        let purchaseRef = purchases.document()
        let commissionRefs = commissions.map { _ in self.commissions.document() }

        try await db.runTransaction { transaction, errorPointer in
            do {
                // Reads first (all reads must precede any write in a Firestore txn).
                let productExists = try prepared.map { try transaction.getDocument($0.productRef).exists }
                var duplicates: [String] = []
                for p in prepared {
                    duplicates.append(contentsOf: try self.duplicateImeis(units: p.group.units, imeiRefs: p.imeiRefs, transaction: transaction))
                }
                if !duplicates.isEmpty {
                    errorPointer?.pointee = Self.duplicateError(duplicates)
                    return nil
                }
                // Writes — stock + purchase + every commission, all in ONE transaction (ticket #97:
                // never stock without its purchase record or its commission obligation).
                for (gi, p) in prepared.enumerated() {
                    if !productExists[gi], let product = p.group.product {
                        transaction.setData(self.productData(skuKey: p.group.skuKey, product: product), forDocument: p.productRef)
                    }
                    self.writeUnits(p.group.units, serialRefs: p.serialRefs, imeiRefs: p.imeiRefs, productId: p.group.skuKey, transaction: transaction)
                }
                transaction.setData(self.purchaseData(purchase), forDocument: purchaseRef)
                for (i, c) in commissions.enumerated() {
                    transaction.setData(self.commissionData(commissionId: commissionRefs[i].documentID, c, sourceBatchId: purchaseRef.documentID), forDocument: commissionRefs[i])
                }
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
    }

    func __updateSerial(serialId: String, edits: SerialEdits) async throws {
        let serialRef = serials.document(serialId)
        try await db.runTransaction { transaction, errorPointer in
            do {
                let snap = try transaction.getDocument(serialRef)
                guard snap.exists else {
                    errorPointer?.pointee = Self.notFound(serialId)
                    return nil
                }
                let oldImei = snap.get("imei") as? String
                let productId = (snap.get("productId") as? String) ?? ""
                let newImei = edits.imei?.trimmed()

                // NOTE: T2's mobile rules pin `imei` immutable — this re-key branch is
                // rejected on mobile by design (a mistyped IMEI is voided + re-added). The
                // test UI never drives it; kept to honor the shared contract for Desktop.
                if let newImei = newImei, newImei != oldImei {
                    let newIndexRef = self.imeiIndex.document(newImei)
                    if try transaction.getDocument(newIndexRef).exists {
                        errorPointer?.pointee = Self.duplicateError([newImei])
                        return nil
                    }
                    if let oldImei = oldImei, !oldImei.isEmpty {
                        transaction.deleteDocument(self.imeiIndex.document(oldImei))
                    }
                    transaction.setData(self.imeiIndexData(imei: newImei, serialId: serialId, productId: productId), forDocument: newIndexRef)
                }

                var updates: [String: Any] = ["updatedAt": FieldValue.serverTimestamp()]
                if let cost = edits.cost { updates["cost"] = cost }
                if let condition = edits.condition { updates["condition"] = condition.name }
                if let location = edits.location { updates["location"] = self.attributeRefData(location) }
                if let newImei = newImei { updates["imei"] = newImei }
                transaction.updateData(updates, forDocument: serialRef)
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
    }

    func __setSerialStatus(serialId: String, status: SerialStatus) async throws {
        let serialRef = serials.document(serialId)
        try await db.runTransaction { transaction, errorPointer in
            do {
                let snap = try transaction.getDocument(serialRef)
                guard snap.exists else {
                    errorPointer?.pointee = Self.notFound(serialId)
                    return nil
                }
                let imei = snap.get("imei") as? String
                let wasInStock = (snap.get("status") as? String) == SerialStatus.inStock.name
                let productId = (snap.get("productId") as? String) ?? ""

                // All reads must precede all writes: pre-read the index for the
                // return-to-stock re-claim BEFORE touching the serial doc.
                let indexRef = (imei?.isEmpty == false) ? self.imeiIndex.document(imei!) : nil
                var indexTaken = false
                if status == SerialStatus.inStock, !wasInStock, let indexRef = indexRef {
                    indexTaken = try transaction.getDocument(indexRef).exists
                }

                transaction.updateData(
                    ["status": status.name, "updatedAt": FieldValue.serverTimestamp()],
                    forDocument: serialRef
                )
                if let indexRef = indexRef, let imei = imei {
                    if status == SerialStatus.inStock {
                        if indexTaken {
                            errorPointer?.pointee = Self.duplicateError([imei])
                            return nil
                        }
                        transaction.setData(self.imeiIndexData(imei: imei, serialId: serialId, productId: productId), forDocument: indexRef)
                    } else {
                        transaction.deleteDocument(indexRef)
                    }
                }
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
    }

    func __archiveSerial(serialId: String) async throws {
        let serialRef = serials.document(serialId)
        try await db.runTransaction { transaction, errorPointer in
            do {
                let snap = try transaction.getDocument(serialRef)
                guard snap.exists else {
                    errorPointer?.pointee = Self.notFound(serialId)
                    return nil
                }
                let imei = snap.get("imei") as? String
                transaction.updateData(
                    ["isActive": false, "updatedAt": FieldValue.serverTimestamp()],
                    forDocument: serialRef
                )
                if let imei = imei, !imei.isEmpty {
                    transaction.deleteDocument(self.imeiIndex.document(imei))
                }
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
    }

    func __isImeiInStock(imei: String) async throws -> KotlinBoolean {
        let doc = try await imeiIndex.document(imei.trimmed()).getDocument()
        return KotlinBoolean(bool: doc.exists)
    }

    func __archiveProduct(productId: String) async throws {
        try await products.document(productId).updateData([
            "isActive": false,
            "updatedAt": FieldValue.serverTimestamp(),
        ])
    }

    func __updateProduct(productId: String, edits: ProductEdits) async throws {
        var updates: [String: Any] = ["updatedAt": FieldValue.serverTimestamp()]
        if let price = edits.defaultSellingPrice { updates["defaultSellingPrice"] = price }
        try await products.document(productId).updateData(updates)
    }

    // MARK: - Transaction helpers

    /// Reads every unit's `imeiIndex` doc (all reads must precede writes) and returns the
    /// IMEIs already held in stock. `getDocument` throws → funnelled to the errorPointer.
    private func duplicateImeis(units: [NewUnit], imeiRefs: [DocumentReference], transaction: Transaction) throws -> [String] {
        var dups: [String] = []
        for (i, ref) in imeiRefs.enumerated() {
            if try transaction.getDocument(ref).exists {
                dups.append(units[i].imei.trimmed())
            }
        }
        return dups
    }

    private func writeUnits(_ units: [NewUnit], serialRefs: [DocumentReference], imeiRefs: [DocumentReference], productId: String, transaction: Transaction) {
        for (i, unit) in units.enumerated() {
            transaction.setData(serialData(serialId: serialRefs[i].documentID, productId: productId, unit: unit), forDocument: serialRefs[i])
            transaction.setData(imeiIndexData(imei: unit.imei.trimmed(), serialId: serialRefs[i].documentID, productId: productId), forDocument: imeiRefs[i])
        }
    }

    // MARK: - Doc builders

    private func productData(skuKey: String, product: NewProduct) -> [String: Any] {
        var attrs: [String: Any] = [:]
        for (type, ref) in product.attributes {
            attrs[type.wire] = attributeRefData(ref)
        }
        return [
            "productId": skuKey,
            "trackingMode": product.trackingMode.name,
            "attributes": attrs,
            "defaultSellingPrice": product.defaultSellingPrice,
            "isActive": true,
            "createdBy": uid,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
        ]
    }

    private func serialData(serialId: String, productId: String, unit: NewUnit) -> [String: Any] {
        return [
            "serialId": serialId,
            "productId": productId,
            "imei": unit.imei.trimmed(),
            "cost": unit.cost,
            "condition": unit.condition.name,
            "status": SerialStatus.inStock.name,
            "location": attributeRefData(unit.location),
            "isActive": true,
            "saleId": NSNull(),
            "createdBy": uid,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
        ]
    }

    private func imeiIndexData(imei: String, serialId: String, productId: String) -> [String: Any] {
        return ["imei": imei, "serialId": serialId, "productId": productId]
    }

    /// The `purchases/{id}` doc, written PENDING inside the inventory txn — the server-side
    /// `onPurchaseWrite` Cloud Function posts it to Humble Ledger and flips it to SYNCED.
    private func purchaseData(_ purchase: PurchaseInput) -> [String: Any] {
        return [
            "partyEntityId": purchase.partyEntityId,
            "totalCost": purchase.totalCost,   // decimal String — never a Double
            "cashPaid": purchase.cashPaid,
            "bankPaid": purchase.bankPaid,
            "syncStatus": "PENDING",
            "createdBy": uid,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
        ]
    }

    /// The `commissions/{id}` doc written in the batch transaction (ticket #97). PENDING with
    /// `sourceBatchId` = the purchase id; the `onCommissionWrite` CF owns syncStatus/hl*.
    /// `paidNow` is `{ method }` or omitted (accrue only). Money fields are decimal Strings.
    private func commissionData(commissionId: String, _ c: CommissionInput, sourceBatchId: String) -> [String: Any] {
        var data: [String: Any] = [
            "commissionId": commissionId,
            "payeeEntityId": c.payeeEntityId,
            "locationAttributeId": c.locationAttributeId,
            "unitCount": c.unitCount,
            "basisAmount": c.basisAmount,
            "amount": c.amount,        // decimal String — never a Double
            "paidCash": c.paidCash,    // give-now split (cash), "0" when none
            "paidBank": c.paidBank,    // give-now split (bank), "0" when none
            "sourceBatchId": sourceBatchId,
            "syncStatus": "PENDING",
            "createdBy": uid,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
        ]
        data["ruleId"] = c.ruleId ?? NSNull()
        return data
    }

    private func attributeRefData(_ ref: AttributeRef) -> [String: Any] {
        return ["attributeId": ref.attributeId, "name": ref.name]
    }

    // MARK: - Errors

    private static func duplicateError(_ imeis: [String]) -> NSError {
        return NSError(
            domain: duplicateDomain,
            code: 409,
            userInfo: [
                NSLocalizedDescriptionKey: "\(duplicateMarker): \(imeis.joined(separator: ","))",
                "imeis": imeis,
            ]
        )
    }

    private static func notFound(_ id: String) -> NSError {
        return NSError(domain: "com.humblesolutions.aromex.Inventory", code: 404,
                       userInfo: [NSLocalizedDescriptionKey: "Unit not found: \(id)"])
    }

    // MARK: - Mapping

    static func mapProduct(id: String, data: [String: Any]) -> Product {
        var attributes: [AttributeType: AttributeRef] = [:]
        if let rawAttrs = data["attributes"] as? [String: Any] {
            for (key, value) in rawAttrs {
                guard let type = AttributeType.companion.fromWire(value: key),
                      let ref = value as? [String: Any] else { continue }
                attributes[type] = AttributeRef(
                    attributeId: (ref["attributeId"] as? String) ?? "",
                    name: (ref["name"] as? String) ?? ""
                )
            }
        }
        return Product(
            productId: (data["productId"] as? String) ?? id,
            trackingMode: TrackingMode.companion.fromWire(value: (data["trackingMode"] as? String) ?? "") ?? TrackingMode.serialized,
            attributes: attributes,
            defaultSellingPrice: (data["defaultSellingPrice"] as? String) ?? "0",
            isActive: (data["isActive"] as? Bool) ?? true
        )
    }

    static func mapSerial(id: String, data: [String: Any]) -> Serial {
        let loc = data["location"] as? [String: Any]
        return Serial(
            serialId: (data["serialId"] as? String) ?? id,
            productId: (data["productId"] as? String) ?? "",
            imei: (data["imei"] as? String) ?? "",
            cost: (data["cost"] as? String) ?? "0",
            condition: Condition.companion.fromWire(value: (data["condition"] as? String) ?? "") ?? Condition.theNew,
            status: SerialStatus.companion.fromWire(value: (data["status"] as? String) ?? "") ?? SerialStatus.inStock,
            location: AttributeRef(
                attributeId: (loc?["attributeId"] as? String) ?? "",
                name: (loc?["name"] as? String) ?? ""
            ),
            isActive: (data["isActive"] as? Bool) ?? true,
            saleId: data["saleId"] as? String,
            // The Add-Inventory batch this unit came in on (ticket #106) — a batch reversal finds
            // its stock by this and nothing else. Null on units added before it existed.
            purchaseId: data["purchaseId"] as? String
        )
    }
}

/// Pre-allocated refs for one SKU group, so the transaction body only reads then writes.
private struct PreparedGroup {
    let group: StockBatchGroup
    let productRef: DocumentReference
    let serialRefs: [DocumentReference]
    let imeiRefs: [DocumentReference]
}

/// Detaches the Firestore snapshot listener when the shared observe `Flow` is cancelled.
final class FirestoreInventoryObservation: NSObject, InventoryObservation {
    private let registration: ListenerRegistration
    init(_ registration: ListenerRegistration) { self.registration = registration }
    func cancel() { registration.remove() }
}

private extension String {
    func trimmed() -> String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
