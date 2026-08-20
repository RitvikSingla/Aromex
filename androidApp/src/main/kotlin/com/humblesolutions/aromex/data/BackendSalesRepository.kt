package com.humblesolutions.aromex.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.snapshots
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.SaleRecordLine
import com.humblesolutions.aromex.model.SaleStatus
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SaleVoidState
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.model.VoidStatus
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.TaxLine
import com.humblesolutions.aromex.repository.SalesRepository
import com.humblesolutions.aromex.util.Money
import android.content.Context
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Android [SalesRepository] (ticket #61). Commits a sale and its stock changes in **one
 * Firestore transaction** (mirror of `BackendInventoryRepository.addStockBatchWithPurchase`):
 * re-check each unit in-stock → flip `SOLD` + `saleId` + delete `imeiIndex` → create the
 * `sales/{id}` doc PENDING. All-or-nothing; the race loser gets [AlreadySoldException] and
 * nothing is written. HL posting is the `onSaleWrite` Cloud Function's concern.
 */
class BackendSalesRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : SalesRepository {

    private val app get() = FirebaseAppFactory.get(context, config)
    private val db get() = Firebase.firestore(app)
    private val functions get() = Firebase.functions(app)

    override suspend fun recordSale(record: SaleRecord): String = withContext(Dispatchers.IO) {
        val saleRef = db.collection(SALES).document()
        val saleId = saleRef.id
        // Pre-allocate every serial + imeiIndex ref outside the txn (all reads must precede
        // all writes in a Firestore transaction).
        val units = record.inventoryLines.map { line ->
            line to db.collection(SERIALS).document(line.serialId)
        }

        db.runTransaction { txn ->
            // ── Reads first: race-safe stock check; capture each unit's authoritative IMEI ──
            val imeis = units.map { (line, serialRef) ->
                val snap = txn.get(serialRef)
                val inStock = snap.exists() &&
                    snap.getString("status") == SerialStatus.IN_STOCK.name &&
                    snap.getBoolean("isActive") == true
                if (!inStock) throw AlreadySoldException(line.imei)
                snap.getString("imei") // from the serial doc, never client input
            }

            // ── Writes ──────────────────────────────────────────────────────────────────
            units.forEachIndexed { i, (_, serialRef) ->
                txn.update(
                    serialRef,
                    mapOf(
                        "status" to SerialStatus.SOLD.name,
                        "saleId" to saleId,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                // Leaving stock releases the in-stock IMEI guard (keyed by the serial's own imei).
                imeis[i]?.takeIf { it.isNotBlank() }
                    ?.let { txn.delete(db.collection(IMEI_INDEX).document(it)) }
            }
            txn.set(saleRef, saleData(record, saleId))
            null
        }.await()

        saleId
    }

    /**
     * Live invoice state for `sales/{saleId}` (ticket #77) via a Firestore snapshot Flow, so the
     * Sale-complete screen resolves in place as the CF issues the PDF. An absent doc → the default
     * (PENDING) — a missing `invoiceStatus` is never treated as failure.
     */
    override fun observeSaleInvoice(saleId: String): Flow<SaleInvoice> =
        db.collection(SALES).document(saleId).snapshots().map { snap ->
            if (snap.exists()) snap.toSaleInvoice() else SaleInvoice()
        }

    /**
     * Requests an immediate re-issue of a `FAILED` invoice (ticket #77) by calling the
     * `retryInvoice` callable Cloud Function. The client may never write `invoice*` fields
     * (Firestore rules), so the server does the privileged re-issue; the live [observeSaleInvoice]
     * stream carries the eventual result. Returns the callable's settled snapshot.
     */
    override suspend fun retryInvoice(saleId: String): SaleInvoice = withContext(Dispatchers.IO) {
        val result = functions
            .getHttpsCallable("retryInvoice")
            .call(mapOf("saleId" to saleId))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.getData() as? Map<String, Any?> ?: emptyMap()
        SaleInvoice(
            status = SaleInvoiceStatus.fromRaw(data["status"] as? String),
            number = data["invoiceNumber"] as? String,
            url = data["invoiceUrl"] as? String,
        )
    }

    /**
     * One page of past sales (ticket #83), newest-first — the Android mirror of the Desktop impl.
     * The list UI is T2, but the interface is shared, so this keeps the data layer in parity: an
     * exact invoice-number search reads by equality and filters the rest client-side; otherwise a
     * paged `createdAt`/`saleId`-ordered query with server-side customer/balance/date filters.
     */
    override suspend fun querySales(query: SalesQuery): SalesPage = withContext(Dispatchers.IO) {
        val col = db.collection(SALES)

        val invoiceNumber = query.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (invoiceNumber != null) {
            val docs = col.whereEqualTo("invoiceNumber", invoiceNumber).get().await().documents
            val rows = docs.map { it.toSaleSummary() }
                .filter { it.matchesClientSide(query) }
                .sortedByDescending { it.createdAtMillis }
            return@withContext SalesPage(rows, nextCursor = null)
        }

        var q: Query = col
        query.customerEntityIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            q = if (ids.size == 1) q.whereEqualTo("customerEntityId", ids.first()) else q.whereIn("customerEntityId", ids)
        }
        if (query.onlyWithBalance) q = q.whereEqualTo("hasOutstandingBalance", true)
        query.dateFromMillis?.let { q = q.whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(it))) }
        query.dateToMillis?.let { q = q.whereLessThanOrEqualTo("createdAt", Timestamp(Date(it))) }

        q = q.orderBy("createdAt", Query.Direction.DESCENDING).orderBy("saleId", Query.Direction.DESCENDING)
        // Reconstruct the boundary row's exact createdAt (seconds + full nanos) so paging never
        // truncates sub-millisecond precision and skips a same-millisecond sale at the boundary.
        query.cursor?.let { c -> q = q.startAfter(Timestamp(c.createdAtMillis / 1000, c.createdAtNanos), c.saleId) }
        q = q.limit(query.pageSize.toLong())

        val docs = q.get().await().documents
        val rows = docs.map { it.toSaleSummary() }
        // Build the cursor from the last raw doc so its createdAt keeps nanosecond precision.
        val nextCursor = if (docs.size == query.pageSize) {
            docs.lastOrNull()?.let { doc ->
                val ts = doc.getTimestamp("createdAt")
                if (ts != null) SalesCursor(ts.toDate().time, doc.getString("saleId") ?: doc.id, ts.nanoseconds) else null
            }
        } else {
            null
        }
        SalesPage(rows, nextCursor)
    }

    override suspend fun getSale(saleId: String): SaleDetail? = withContext(Dispatchers.IO) {
        val snap = db.collection(SALES).document(saleId).get().await()
        if (snap.exists()) snap.toSaleDetail() else null
    }

    /**
     * Voids a sale (ticket #85) by calling the admin-checked `voidSale` callable — the mobile
     * transport (the client may never write the sale doc). The CF verifies admin server-side and
     * runs the idempotent full reversal. Mobile has no void UI in v1; this keeps the shared
     * interface satisfied and lets a phone adopt voiding later without a functions change.
     */
    override suspend fun voidSale(saleId: String, reason: String): Unit = withContext(Dispatchers.IO) {
        functions.getHttpsCallable("voidSale")
            .call(mapOf("saleId" to saleId, "reason" to reason))
            .await()
    }

    /** Serial indirection for IMEI search (ticket #83): `serials where imei == X → serial.saleId`. */
    override suspend fun findSaleIdByImei(imei: String): String? = withContext(Dispatchers.IO) {
        val trimmed = imei.trim()
        if (trimmed.isEmpty()) return@withContext null
        val serial = db.collection(SERIALS)
            .whereEqualTo("imei", trimmed).limit(1).get().await().documents.firstOrNull()
        serial?.getString("saleId")?.takeIf { it.isNotBlank() }
    }

    private fun SaleSummary.matchesClientSide(query: SalesQuery): Boolean {
        if (query.onlyWithBalance && Money.isZero(balanceRemaining)) return false
        query.dateFromMillis?.let { if (createdAtMillis < it) return false }
        query.dateToMillis?.let { if (createdAtMillis > it) return false }
        query.customerEntityIds?.let { if (customerEntityId !in it) return false }
        return true
    }

    private fun DocumentSnapshot.toSaleSummary(): SaleSummary {
        val lines = parseLines()
        val firstInventory = lines.firstOrNull { it is SaleRecordLine.Inventory } as? SaleRecordLine.Inventory
        return SaleSummary(
            saleId = getString("saleId") ?: id,
            createdAtMillis = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            customerEntityId = getString("customerEntityId").orEmpty(),
            isWalkIn = getBoolean("isWalkIn") == true,
            buyerName = getString("buyerName"),
            firstItemLabel = firstInventory?.label
                ?: (lines.firstOrNull() as? SaleRecordLine.Custom)?.name
                ?: "",
            itemCount = lines.size,
            firstImei = firstInventory?.imei,
            itemLabels = lines.map {
                when (it) {
                    is SaleRecordLine.Inventory -> it.label
                    is SaleRecordLine.Custom -> it.name
                }
            },
            imeis = lines.filterIsInstance<SaleRecordLine.Inventory>().map { it.imei },
            grandTotal = getString("grandTotal") ?: "0",
            amountPaid = getString("amountPaid") ?: "0",
            balanceRemaining = getString("balanceRemaining") ?: "0",
            syncStatus = HlSyncStatus.fromWire(getString("syncStatus").orEmpty()),
            invoiceNumber = getString("invoiceNumber"),
            invoiceStatus = SaleInvoiceStatus.fromRaw(getString("invoiceStatus")),
            status = SaleStatus.fromWire(getString("status")),
        )
    }

    private fun DocumentSnapshot.toSaleDetail(): SaleDetail {
        val payments = get("payments") as? Map<*, *>
        return SaleDetail(
            saleId = getString("saleId") ?: id,
            createdAtMillis = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            createdBy = getString("createdBy").orEmpty(),
            customerEntityId = getString("customerEntityId").orEmpty(),
            isWalkIn = getBoolean("isWalkIn") == true,
            buyerName = getString("buyerName"),
            buyerPhone = getString("buyerPhone"),
            lines = parseLines(),
            subtotal = getString("subtotal") ?: "0",
            saleDiscount = getString("saleDiscount") ?: "0",
            taxableAmount = getString("taxableAmount") ?: "0",
            taxLines = (get("taxLines") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map {
                TaxLine(
                    name = it["name"] as? String ?: "",
                    rate = it["rate"] as? String ?: "0",
                    amount = it["amount"] as? String ?: "0",
                )
            },
            taxTotal = getString("taxTotal") ?: "0",
            grandTotal = getString("grandTotal") ?: "0",
            cogsTotal = getString("cogsTotal") ?: "0",
            payment = PaymentInput(
                cash = payments?.get("cash") as? String ?: "0",
                card = payments?.get("card") as? String ?: "0",
                bank = payments?.get("bank") as? String ?: "0",
            ),
            amountPaid = getString("amountPaid") ?: "0",
            balanceRemaining = getString("balanceRemaining") ?: "0",
            note = getString("note"),
            syncStatus = HlSyncStatus.fromWire(getString("syncStatus").orEmpty()),
            invoice = toSaleInvoice(),
            status = SaleStatus.fromWire(getString("status")),
            voidState = SaleVoidState(
                status = VoidStatus.fromWire(getString("voidStatus")),
                reason = getString("voidReason"),
                voidedAtMillis = getTimestamp("voidedAt")?.toDate()?.time,
                error = getString("voidError"),
            ),
        )
    }

    private fun DocumentSnapshot.parseLines(): List<SaleRecordLine> =
        (get("lines") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { m ->
            fun str(key: String): String = m[key] as? String ?: ""
            when (m["kind"]) {
                "INVENTORY" -> SaleRecordLine.Inventory(
                    productId = str("productId"),
                    serialId = str("serialId"),
                    imei = str("imei"),
                    label = str("label"),
                    listPrice = str("listPrice"),
                    unitPrice = str("unitPrice"),
                    lineDiscount = str("lineDiscount"),
                    netPrice = str("netPrice"),
                    cost = str("cost"),
                )
                else -> SaleRecordLine.Custom(
                    name = str("name"),
                    unitPrice = str("unitPrice"),
                    lineDiscount = str("lineDiscount"),
                    netPrice = str("netPrice"),
                )
            }
        }

    private fun DocumentSnapshot.toSaleInvoice(): SaleInvoice = SaleInvoice(
        status = SaleInvoiceStatus.fromRaw(getString("invoiceStatus")),
        number = getString("invoiceNumber"),
        url = getString("invoiceUrl"),
        error = getString("invoiceError"),
    )

    private fun saleData(record: SaleRecord, saleId: String): Map<String, Any?> = mapOf(
        "saleId" to saleId,
        "customerEntityId" to record.customerEntityId,
        "isWalkIn" to record.isWalkIn,
        "lines" to record.lines.map { lineData(it) },
        "subtotal" to record.subtotal,
        "saleDiscount" to record.saleDiscount,
        "taxableAmount" to record.taxableAmount,
        "taxLines" to record.taxLines.map {
            mapOf("name" to it.name, "rate" to it.rate, "amount" to it.amount)
        },
        "taxTotal" to record.taxTotal,
        "grandTotal" to record.grandTotal,
        "cogsTotal" to record.cogsTotal,
        "payments" to mapOf(
            "cash" to record.payment.cash,
            "card" to record.payment.card,
            "bank" to record.payment.bank,
        ),
        "amountPaid" to record.amountPaid,
        "balanceRemaining" to record.balanceRemaining,
        // Denormalized flag for the Sales History "only with a balance" filter (ticket #83):
        // money is a decimal STRING and Firestore orders strings lexicographically, so an
        // inequality query on balanceRemaining is silently wrong. Evaluate with Money here.
        "hasOutstandingBalance" to !Money.isZero(record.balanceRemaining),
        "note" to record.note,
        // Walk-in buyer capture (ticket #77); null for a named customer or when left blank.
        "buyerName" to record.buyerName,
        "buyerPhone" to record.buyerPhone,
        // Tax-inclusive pricing snapshot (ticket #106): how this sale was priced, and the buyer's
        // snapshotted tax number (null for a walk-in or a customer without one).
        "taxInclusive" to record.taxInclusive,
        "buyerTaxNumber" to record.buyerTaxNumber,
        "status" to "COMPLETED",
        "syncStatus" to HlSyncStatus.PENDING.name,
        "createdBy" to record.createdBy,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    private fun lineData(line: SaleRecordLine): Map<String, Any?> = when (line) {
        is SaleRecordLine.Inventory -> mapOf(
            "kind" to "INVENTORY",
            "productId" to line.productId,
            "serialId" to line.serialId,
            "imei" to line.imei,
            "label" to line.label,
            "listPrice" to line.listPrice,
            "unitPrice" to line.unitPrice,
            "lineDiscount" to line.lineDiscount,
            "netPrice" to line.netPrice,
            "cost" to line.cost,
        )
        is SaleRecordLine.Custom -> mapOf(
            "kind" to "CUSTOM",
            "name" to line.name,
            "unitPrice" to line.unitPrice,
            "lineDiscount" to line.lineDiscount,
            "netPrice" to line.netPrice,
        )
    }

    private companion object {
        const val SALES = "sales"
        const val SERIALS = "serials"
        const val IMEI_INDEX = "imeiIndex"
    }
}
