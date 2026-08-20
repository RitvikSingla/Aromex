import Foundation
import FirebaseFirestore
import FirebaseFunctions
import SharedLogic

/// Firestore-backed `SalesRepository` — the iOS native-SDK impl of the shared contract
/// (ticket #61). Mirrors the Android/Desktop `BackendSalesRepository`: commit a sale and
/// its stock changes in **one transaction** — re-check each unit in-stock, flip `SOLD` +
/// `saleId` + delete `imeiIndex`, and create the `sales/{id}` doc PENDING. All-or-nothing;
/// HL posting is the `onSaleWrite` Cloud Function's concern.
///
/// ── Already-sold (the race loser) ─────────────────────────────────────────────────
/// A Kotlin exception cannot be *thrown* from Swift, so a unit that is no longer in stock
/// at commit is signalled with an `NSError` in `Self.alreadySoldDomain` whose message
/// carries `Self.alreadySoldMarker` and an `imei`; the ViewModel (T2) detects it and shows
/// the graceful "already sold" message. The write stays atomic — nothing is committed.
final class BackendSalesRepository: NSObject, SalesRepository {

    static let alreadySoldDomain = "com.humblesolutions.aromex.AlreadySold"
    static let alreadySoldMarker = "AROMEX_ALREADY_SOLD"

    private let db: Firestore
    private let functions: Functions

    init(config: FirebaseClientConfig) {
        self.db = FirebaseAppFactory.firestore(for: config)
        self.functions = Functions.functions(app: FirebaseAppFactory.app(for: config))
        super.init()
    }

    private var sales: CollectionReference { db.collection("sales") }
    private var serials: CollectionReference { db.collection("serials") }
    private var imeiIndex: CollectionReference { db.collection("imeiIndex") }

    func __recordSale(record: SaleRecord) async throws -> String {
        let saleRef = sales.document()
        let saleId = saleRef.documentID
        let invLines = record.inventoryLines
        let serialRefs = invLines.map { serials.document($0.serialId) }

        try await db.runTransaction { transaction, errorPointer in
            do {
                // ── Reads first: race-safe stock check; capture each unit's authoritative IMEI ──
                var imeis: [String?] = []
                for (i, line) in invLines.enumerated() {
                    let snap = try transaction.getDocument(serialRefs[i])
                    let inStock = snap.exists
                        && (snap.get("status") as? String) == SerialStatus.inStock.name
                        && (snap.get("isActive") as? Bool) == true
                    if !inStock {
                        errorPointer?.pointee = Self.alreadySoldError(line.imei)
                        return nil
                    }
                    imeis.append(snap.get("imei") as? String) // from the serial doc, never client input
                }

                // ── Writes ──────────────────────────────────────────────────────────────
                for (i, _) in invLines.enumerated() {
                    transaction.updateData(
                        [
                            "status": SerialStatus.sold.name,
                            "saleId": saleId,
                            "updatedAt": FieldValue.serverTimestamp(),
                        ],
                        forDocument: serialRefs[i]
                    )
                    // Leaving stock releases the in-stock IMEI guard (keyed by the serial's own imei).
                    if let imei = imeis[i], !imei.isEmpty {
                        transaction.deleteDocument(self.imeiIndex.document(imei))
                    }
                }
                transaction.setData(self.saleData(record, saleId: saleId), forDocument: saleRef)
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
        return saleId
    }

    // MARK: - Live invoice read (produces the shared Flow the use case consumes)

    /// A Kotlin `Flow` of the sale's invoice state (ticket #77), driven by a Firestore snapshot
    /// listener on `sales/{saleId}`. Built with the shared `saleInvoiceCallbackFlow` adapter so it
    /// flows straight into `ObserveSaleInvoiceUseCase`, resolving the Sale-complete row in place as
    /// the CF issues the PDF. An absent doc → the default (PENDING) — never treated as failure.
    func observeSaleInvoice(saleId: String) -> SkieSwiftFlow<SaleInvoice> {
        return saleInvoiceCallbackFlow { onEach, onError in
            let registration = self.sales.document(saleId)
                .addSnapshotListener { snapshot, error in
                    if let error = error {
                        _ = onError(error.localizedDescription)
                        return
                    }
                    let invoice = (snapshot?.exists == true)
                        ? Self.mapInvoice(snapshot!.data() ?? [:])
                        : SaleInvoice(status: .pending, number: nil, url: nil, error: nil)
                    _ = onEach(invoice)
                }
            return FirestoreSaleInvoiceObservation(registration)
        }
    }

    /// Requests an immediate re-issue of a `FAILED` invoice (ticket #77) by calling the
    /// `retryInvoice` callable Cloud Function. The client may never write `invoice*` fields
    /// (Firestore rules), so the server does the privileged re-issue; the live
    /// `observeSaleInvoice` stream carries the eventual result. SKIE `__`-prefixes the suspend
    /// member (#34).
    func __retryInvoice(saleId: String) async throws -> SaleInvoice {
        let result = try await functions.httpsCallable("retryInvoice").call(["saleId": saleId])
        let data = (result.data as? [String: Any]) ?? [:]
        return SaleInvoice(
            status: SaleInvoiceStatus.companion.fromRaw(raw: data["status"] as? String),
            number: data["invoiceNumber"] as? String,
            url: data["invoiceUrl"] as? String,
            error: nil
        )
    }

    /// Voids a sale (ticket #85) by calling the admin-checked `voidSale` callable — the mobile
    /// transport (the client may never write the sale doc). The CF verifies admin server-side and
    /// runs the idempotent full reversal. Phones have no void UI in v1; this satisfies the shared
    /// interface and lets iOS adopt voiding later without a functions change. SKIE `__`-prefixes it.
    func __voidSale(saleId: String, reason: String) async throws {
        _ = try await functions.httpsCallable("voidSale").call(["saleId": saleId, "reason": reason])
    }

    // MARK: - Sales History reads (ticket #83 contract, consumed by the phones in #84)

    /// One page of past sales, newest-first (ticket #83). Mirrors the Android/Desktop impl:
    /// filters run as Firestore queries and results page via the cursor — never a full-collection
    /// load (sales are unbounded). IMEI is **not** handled here (it can't be queried on the sale
    /// doc); the use case resolves it via `findSaleIdByImei` → `getSale`. The balance filter reads
    /// the denormalized `hasOutstandingBalance` boolean, never a string compare of `balanceRemaining`.
    /// SKIE `__`-prefixes the suspend member (#34).
    func __querySales(query: SalesQuery) async throws -> SalesPage {
        let col = sales

        // Invoice-number lookup: an equality match anywhere in history, filtered client-side for
        // the remaining facets (no cursor — the result set is tiny).
        if let invoiceNumber = query.invoiceNumber?.trimmingCharacters(in: .whitespacesAndNewlines),
           !invoiceNumber.isEmpty {
            let snap = try await col.whereField("invoiceNumber", isEqualTo: invoiceNumber).getDocuments()
            let rows = snap.documents
                .map { Self.toSaleSummary($0) }
                .filter { Self.matchesClientSide($0, query) }
                .sorted { $0.createdAtMillis > $1.createdAtMillis }
            return SalesPage(sales: rows, nextCursor: nil)
        }

        var q: Query = col
        if let ids = query.customerEntityIds, !ids.isEmpty {
            q = ids.count == 1
                ? q.whereField("customerEntityId", isEqualTo: ids[0])
                : q.whereField("customerEntityId", in: ids)
        }
        if query.onlyWithBalance { q = q.whereField("hasOutstandingBalance", isEqualTo: true) }
        if let from = query.dateFromMillis {
            q = q.whereField("createdAt", isGreaterThanOrEqualTo: Timestamp(date: Date(timeIntervalSince1970: Double(truncating: from) / 1000.0)))
        }
        if let to = query.dateToMillis {
            q = q.whereField("createdAt", isLessThanOrEqualTo: Timestamp(date: Date(timeIntervalSince1970: Double(truncating: to) / 1000.0)))
        }

        q = q.order(by: "createdAt", descending: true).order(by: "saleId", descending: true)
        // Reconstruct the boundary row's exact createdAt (seconds + full nanos) so paging never
        // truncates sub-millisecond precision and skips a same-millisecond sale at the boundary.
        if let c = query.cursor {
            let ts = Timestamp(seconds: c.createdAtMillis / 1000, nanoseconds: c.createdAtNanos)
            q = q.start(after: [ts, c.saleId])
        }
        let pageSize = Int(query.pageSize)
        q = q.limit(to: pageSize)

        let docs = try await q.getDocuments().documents
        let rows = docs.map { Self.toSaleSummary($0) }
        // Build the cursor from the last raw doc so its createdAt keeps nanosecond precision.
        var nextCursor: SalesCursor? = nil
        if docs.count == pageSize, let last = docs.last,
           let ts = last.data()["createdAt"] as? Timestamp {
            let saleId = last.data()["saleId"] as? String ?? last.documentID
            nextCursor = SalesCursor(
                createdAtMillis: Int64(ts.seconds) * 1000 + Int64(ts.nanoseconds) / 1_000_000,
                saleId: saleId,
                createdAtNanos: ts.nanoseconds
            )
        }
        return SalesPage(sales: rows, nextCursor: nextCursor)
    }

    /// The full record of a single sale (ticket #83) — a direct `sales/{saleId}` read for the
    /// detail view. Returns nil if the sale doesn't exist. SKIE `__`-prefixes the suspend member.
    func __getSale(saleId: String) async throws -> SaleDetail? {
        let snap = try await sales.document(saleId).getDocument()
        guard snap.exists, let data = snap.data() else { return nil }
        return Self.toSaleDetail(data, docId: snap.documentID)
    }

    /// Resolves an IMEI to the id of the sale that sold that unit (ticket #83), or nil. Goes
    /// through `serials where imei == X → serial.saleId` (the IMEI is buried in the sale's `lines`
    /// array and `imeiIndex/{imei}` is deleted once a unit sells). SKIE `__`-prefixes the suspend.
    func __findSaleIdByImei(imei: String) async throws -> String? {
        let trimmed = imei.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let snap = try await serials.whereField("imei", isEqualTo: trimmed).limit(to: 1).getDocuments()
        let saleId = snap.documents.first?.data()["saleId"] as? String
        return (saleId?.isEmpty == false) ? saleId : nil
    }

    // MARK: - History doc mappers (Firestore → shared read models)

    private static func matchesClientSide(_ s: SaleSummary, _ query: SalesQuery) -> Bool {
        if query.onlyWithBalance && Money.shared.isZero(value: s.balanceRemaining) { return false }
        if let from = query.dateFromMillis, s.createdAtMillis < Int64(truncating: from) { return false }
        if let to = query.dateToMillis, s.createdAtMillis > Int64(truncating: to) { return false }
        if let ids = query.customerEntityIds, !ids.contains(s.customerEntityId) { return false }
        return true
    }

    private static func toSaleSummary(_ doc: QueryDocumentSnapshot) -> SaleSummary {
        let data = doc.data()
        let lines = parseLines(data["lines"])
        let firstInventory = lines.compactMap { $0 as? SaleRecordLineInventory }.first
        let firstCustomName = (lines.first as? SaleRecordLineCustom)?.name
        return SaleSummary(
            saleId: data["saleId"] as? String ?? doc.documentID,
            createdAtMillis: millis(data["createdAt"]),
            customerEntityId: data["customerEntityId"] as? String ?? "",
            isWalkIn: data["isWalkIn"] as? Bool ?? false,
            buyerName: data["buyerName"] as? String,
            firstItemLabel: firstInventory?.label ?? firstCustomName ?? "",
            itemCount: Int32(lines.count),
            firstImei: firstInventory?.imei,
            itemLabels: lines.map { line in
                (line as? SaleRecordLineInventory)?.label ?? (line as? SaleRecordLineCustom)?.name ?? ""
            },
            imeis: lines.compactMap { ($0 as? SaleRecordLineInventory)?.imei },
            grandTotal: data["grandTotal"] as? String ?? "0",
            amountPaid: data["amountPaid"] as? String ?? "0",
            balanceRemaining: data["balanceRemaining"] as? String ?? "0",
            syncStatus: HlSyncStatus.companion.fromWire(value: data["syncStatus"] as? String ?? ""),
            invoiceNumber: data["invoiceNumber"] as? String,
            invoiceStatus: SaleInvoiceStatus.companion.fromRaw(raw: data["invoiceStatus"] as? String),
            status: SaleStatus.companion.fromWire(value: data["status"] as? String)
        )
    }

    private static func toSaleDetail(_ data: [String: Any], docId: String) -> SaleDetail {
        let payments = data["payments"] as? [String: Any]
        let taxLines = (data["taxLines"] as? [[String: Any]] ?? []).map {
            TaxLine(
                name: $0["name"] as? String ?? "",
                rate: $0["rate"] as? String ?? "0",
                amount: $0["amount"] as? String ?? "0"
            )
        }
        return SaleDetail(
            saleId: data["saleId"] as? String ?? docId,
            createdAtMillis: millis(data["createdAt"]),
            createdBy: data["createdBy"] as? String ?? "",
            customerEntityId: data["customerEntityId"] as? String ?? "",
            isWalkIn: data["isWalkIn"] as? Bool ?? false,
            buyerName: data["buyerName"] as? String,
            buyerPhone: data["buyerPhone"] as? String,
            lines: parseLines(data["lines"]),
            subtotal: data["subtotal"] as? String ?? "0",
            saleDiscount: data["saleDiscount"] as? String ?? "0",
            taxableAmount: data["taxableAmount"] as? String ?? "0",
            taxLines: taxLines,
            taxTotal: data["taxTotal"] as? String ?? "0",
            grandTotal: data["grandTotal"] as? String ?? "0",
            cogsTotal: data["cogsTotal"] as? String ?? "0",
            payment: PaymentInput(
                cash: payments?["cash"] as? String ?? "0",
                card: payments?["card"] as? String ?? "0",
                bank: payments?["bank"] as? String ?? "0"
            ),
            amountPaid: data["amountPaid"] as? String ?? "0",
            balanceRemaining: data["balanceRemaining"] as? String ?? "0",
            note: data["note"] as? String,
            syncStatus: HlSyncStatus.companion.fromWire(value: data["syncStatus"] as? String ?? ""),
            invoice: SaleInvoice(
                status: SaleInvoiceStatus.companion.fromRaw(raw: data["invoiceStatus"] as? String),
                number: data["invoiceNumber"] as? String,
                url: data["invoiceUrl"] as? String,
                error: data["invoiceError"] as? String
            ),
            status: SaleStatus.companion.fromWire(value: data["status"] as? String),
            voidState: SaleVoidState(
                status: VoidStatus.companion.fromWire(value: data["voidStatus"] as? String),
                reason: data["voidReason"] as? String,
                voidedAtMillis: nil,
                error: data["voidError"] as? String
            )
        )
    }

    private static func parseLines(_ raw: Any?) -> [SaleRecordLine] {
        guard let arr = raw as? [[String: Any]] else { return [] }
        return arr.map { m in
            func str(_ key: String) -> String { m[key] as? String ?? "" }
            if m["kind"] as? String == "INVENTORY" {
                return SaleRecordLineInventory(
                    productId: str("productId"),
                    serialId: str("serialId"),
                    imei: str("imei"),
                    label: str("label"),
                    listPrice: str("listPrice"),
                    unitPrice: str("unitPrice"),
                    lineDiscount: str("lineDiscount"),
                    netPrice: str("netPrice"),
                    cost: str("cost")
                )
            }
            return SaleRecordLineCustom(
                name: str("name"),
                unitPrice: str("unitPrice"),
                lineDiscount: str("lineDiscount"),
                netPrice: str("netPrice")
            )
        }
    }

    /// Firestore `Timestamp` → epoch millis (UTC), preserving sub-second precision. 0 when absent.
    private static func millis(_ raw: Any?) -> Int64 {
        guard let ts = raw as? Timestamp else { return 0 }
        return Int64(ts.seconds) * 1000 + Int64(ts.nanoseconds) / 1_000_000
    }

    /// Firestore `sales/{id}` doc fields → shared `SaleInvoice`.
    private static func mapInvoice(_ data: [String: Any]) -> SaleInvoice {
        return SaleInvoice(
            status: SaleInvoiceStatus.companion.fromRaw(raw: data["invoiceStatus"] as? String),
            number: data["invoiceNumber"] as? String,
            url: data["invoiceUrl"] as? String,
            error: data["invoiceError"] as? String
        )
    }

    // MARK: - Doc builders

    private func saleData(_ record: SaleRecord, saleId: String) -> [String: Any] {
        return [
            "saleId": saleId,
            "customerEntityId": record.customerEntityId,
            "isWalkIn": record.isWalkIn,
            "lines": record.lines.map { lineData($0) },
            "subtotal": record.subtotal,
            "saleDiscount": record.saleDiscount,
            "taxableAmount": record.taxableAmount,
            "taxLines": record.taxLines.map { ["name": $0.name, "rate": $0.rate, "amount": $0.amount] },
            "taxTotal": record.taxTotal,
            "grandTotal": record.grandTotal,
            "cogsTotal": record.cogsTotal,
            "payments": [
                "cash": record.payment.cash,
                "card": record.payment.card,
                "bank": record.payment.bank,
            ],
            "amountPaid": record.amountPaid,
            "balanceRemaining": record.balanceRemaining,
            "note": record.note ?? NSNull(),
            // Walk-in buyer capture (ticket #77); null for a named customer or when left blank.
            "buyerName": record.buyerName ?? NSNull(),
            "buyerPhone": record.buyerPhone ?? NSNull(),
            // Tax-inclusive pricing snapshot (ticket #106): how this sale was priced, and the
            // buyer's snapshotted tax number (null for a walk-in or a customer without one).
            "taxInclusive": record.taxInclusive,
            "buyerTaxNumber": record.buyerTaxNumber ?? NSNull(),
            "status": "COMPLETED",
            "syncStatus": "PENDING",
            "createdBy": record.createdBy,
            "createdAt": FieldValue.serverTimestamp(),
            "updatedAt": FieldValue.serverTimestamp(),
        ]
    }

    private func lineData(_ line: SaleRecordLine) -> [String: Any] {
        if let inv = line as? SaleRecordLineInventory {
            return [
                "kind": "INVENTORY",
                "productId": inv.productId,
                "serialId": inv.serialId,
                "imei": inv.imei,
                "label": inv.label,
                "listPrice": inv.listPrice,
                "unitPrice": inv.unitPrice,
                "lineDiscount": inv.lineDiscount,
                "netPrice": inv.netPrice,
                "cost": inv.cost,
            ]
        }
        let custom = line as! SaleRecordLineCustom
        return [
            "kind": "CUSTOM",
            "name": custom.name,
            "unitPrice": custom.unitPrice,
            "lineDiscount": custom.lineDiscount,
            "netPrice": custom.netPrice,
        ]
    }

    // MARK: - Errors

    private static func alreadySoldError(_ imei: String) -> NSError {
        return NSError(
            domain: alreadySoldDomain,
            code: 409,
            userInfo: [
                NSLocalizedDescriptionKey: "\(alreadySoldMarker): \(imei)",
                "imei": imei,
            ]
        )
    }
}

/// Detaches the Firestore snapshot listener when the shared sale-invoice observe `Flow` is
/// cancelled. Conforms to the shared `SaleInvoiceObservation` handle returned by the
/// `saleInvoiceCallbackFlow` adapter.
private final class FirestoreSaleInvoiceObservation: NSObject, SaleInvoiceObservation {
    private let registration: ListenerRegistration
    init(_ registration: ListenerRegistration) { self.registration = registration }
    func cancel() { registration.remove() }
}
