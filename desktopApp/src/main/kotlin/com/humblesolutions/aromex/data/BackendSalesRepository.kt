package com.humblesolutions.aromex.data

import com.google.api.core.ApiFuture
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2CredentialsWithRefresh
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.Transaction
import com.google.cloud.firestore.TransactionOptions
import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.InvoiceRetryNotAcknowledgedException
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.SaleContentionException
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.SaleRecordLine
import com.humblesolutions.aromex.model.SaleStatus
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SaleVoidFailedException
import com.humblesolutions.aromex.model.SaleVoidState
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.TaxLine
import com.humblesolutions.aromex.model.VoidStatus
import com.humblesolutions.aromex.repository.SalesRepository
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop [SalesRepository] (ticket #61), Admin SDK. Commits a sale and its stock changes
 * in **one Firestore transaction** (mirror of `BackendInventoryRepository`): re-check each
 * unit in-stock → flip `SOLD` + `saleId` + delete `imeiIndex` → create the `sales/{id}` doc
 * PENDING. All-or-nothing; the race loser gets [AlreadySoldException]. The Admin SDK
 * bypasses Firestore rules, so the permission gate lives in `RecordSaleUseCase`.
 */
class BackendSalesRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
    private val uid: String,
) : SalesRepository {

    private val clientLock = Mutex()
    @Volatile private var cached: Firestore? = null

    private suspend fun firestore(): Firestore = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        clientLock.withLock {
            cached?.let { return@withLock it }
            val credentials = OAuth2CredentialsWithRefresh.newBuilder()
                .setAccessToken(brokeredAccessToken())
                .setRefreshHandler { runBlocking { brokeredAccessToken() } }
                .build()
            val firestore = FirestoreOptions.newBuilder()
                .setProjectId(config.projectId)
                .setCredentials(credentials)
                .build()
                .service
            cached = firestore
            firestore
        }
    }

    /**
     * The credential, stamped with the expiry the gateway actually issued.
     *
     * Never invent a lifetime here: the gateway's TTL tracks the underlying Google token and has
     * been seen anywhere from ~24 to ~60 minutes. Guessing 50 meant that whenever the real one was
     * shorter, the SDK kept sending a dead token — and because it believed the token was still
     * good, it never called the refresh handler. That surfaced as UNAUTHENTICATED partway through
     * a session, with nothing in the app having changed.
     */
    private suspend fun brokeredAccessToken(): AccessToken {
        val token = broker.currentTokenWithExpiry()
        return AccessToken(token.value, Date(token.expiresAtMs))
    }

    override suspend fun recordSale(record: SaleRecord): String = withContext(Dispatchers.IO) {
        val db = firestore()
        val saleRef = db.collection(SALES).document()
        val saleId = saleRef.id
        val units = record.inventoryLines.map { line ->
            line to db.collection(SERIALS).document(line.serialId)
        }

        try {
            db.runTransaction(
                Transaction.Function<Void?> { txn ->
                    // ── Reads first: race-safe stock check; capture each unit's authoritative IMEI ──
                    val imeis = units.map { (line, serialRef) ->
                        val snap = txn.get(serialRef).get()
                        val inStock = snap.exists() &&
                            snap.getString("status") == SerialStatus.IN_STOCK.name &&
                            snap.getBoolean("isActive") == true
                        if (!inStock) throw AlreadySoldException(line.imei)
                        snap.getString("imei") // from the serial doc, never client input
                    }

                    // ── Writes ──────────────────────────────────────────────────────────────
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
                },
                // More attempts than the default 5: two cashiers committing the same unit at the
                // exact same instant can starve each other (livelock) and both exhaust their
                // retries → "too many retries". The SDK spaces attempts with randomized backoff,
                // so extra attempts let one commit and the other cleanly re-read the unit as SOLD
                // (→ AlreadySoldException) instead of both timing out.
                TransactionOptions.createReadWriteOptionsBuilder()
                    .setNumberOfAttempts(MAX_TXN_ATTEMPTS)
                    .build(),
            ).awaitSaleTxn()
        } catch (e: AlreadySoldException) {
            throw e // already the clean, line-aware signal — pass it straight through.
        } catch (e: Exception) {
            // Any other transaction failure here is almost always write contention / "too many
            // retries" (no validation/permission error can reach this far — those gate earlier).
            // Re-read the units to tell a genuinely-lost race from transient contention.
            throw classifyContentionFailure(units, e)
        }

        saleId
    }

    /**
     * Called when the sale transaction fails for a non-[AlreadySoldException] reason (see the
     * catch in [recordSale] — practically always Firestore write contention / "too many
     * retries"). Re-reads each unit **outside** a transaction: if one is no longer sellable the
     * race was lost → [AlreadySoldException] so the UI can drop that line; if everything is
     * still in stock nothing was committed → [SaleContentionException] (retry). If even the
     * re-read fails, the [original] error is surfaced unchanged.
     */
    private fun classifyContentionFailure(
        units: List<Pair<SaleRecordLine.Inventory, DocumentReference>>,
        original: Throwable,
    ): Throwable = try {
        val gone = units.firstOrNull { (_, serialRef) ->
            val snap = serialRef.get().get()
            !(
                snap.exists() &&
                    snap.getString("status") == SerialStatus.IN_STOCK.name &&
                    snap.getBoolean("isActive") == true
                )
        }
        if (gone != null) AlreadySoldException(gone.first.imei) else SaleContentionException()
    } catch (reReadFailure: Exception) {
        original
    }

    /**
     * Live invoice state for `sales/{saleId}` (ticket #77), via an Admin-SDK snapshot listener.
     * Emits [SaleInvoice] on every change to the doc so the Sale-complete screen resolves in
     * place as the CF issues the PDF. An absent/deleted doc → the default (PENDING) — a missing
     * `invoiceStatus` is never treated as failure.
     */
    override fun observeSaleInvoice(saleId: String): Flow<SaleInvoice> = callbackFlow {
        val db = firestore()
        val registration = db.collection(SALES).document(saleId)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null && snapshot.exists() -> trySend(snapshot.toSaleInvoice())
                    else -> trySend(SaleInvoice())
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Requests an immediate re-issue of a `FAILED` invoice (ticket #77). Desktop's Admin SDK
     * bypasses Firestore rules, so it bumps `invoiceRetryRequestedAt` directly; `onSaleWrite`
     * edge-triggers on that change and re-runs T1's idempotent issuance.
     *
     * The nudge landing proves nothing on its own — unlike mobile's callable, an Admin-SDK write
     * succeeds whether or not any function is listening. So we then wait for the backend to
     * *acknowledge*: `retryInvoiceCore` flips the doc out of `FAILED` (to `PENDING`) before it
     * re-renders, and that flip is the only observable proof the trigger ran. No flip inside
     * [RETRY_ACK_TIMEOUT] means functions aren't deployed (or the trigger is broken), which is
     * reported as [InvoiceRetryNotAcknowledgedException] rather than as a silent success.
     *
     * Returns as soon as the attempt is under way, **not** when the PDF is done — the render can
     * take ~30 s on a cold Lambda, and once the doc reads `PENDING` the UI already shows
     * "preparing" and hides Retry. The live [observeSaleInvoice] stream carries the outcome.
     *
     * The listener attaches just after the nudge, so in principle a re-issue that ran *and* failed
     * again entirely within that millisecond gap would read as unacknowledged. A full HL post plus
     * engine render can't finish that fast; and if it ever did, the sale and the books are still
     * correct and reconcile still owns the invoice — only the explanatory line would be wrong.
     */
    override suspend fun retryInvoice(saleId: String): SaleInvoice = withContext(Dispatchers.IO) {
        val db = firestore()
        val ref = db.collection(SALES).document(saleId)
        ref.update(
            mapOf(
                "invoiceRetryRequestedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).get()
        withTimeoutOrNull(RETRY_ACK_TIMEOUT) {
            observeSaleInvoice(saleId).first { it.status != SaleInvoiceStatus.FAILED }
        } ?: throw InvoiceRetryNotAcknowledgedException()
    }

    /**
     * One page of past sales (ticket #83), newest-first, filtered server-side. Two shapes:
     *
     * - **Exact invoice-number search** ([SalesQuery.invoiceNumber] set): a single equality read
     *   (`whereEqualTo("invoiceNumber", …)`, no `orderBy` → the auto single-field index, no
     *   composite needed). The remaining filters (customer / balance / date) are applied to that
     *   handful of results client-side, and the page carries no cursor — an invoice number targets
     *   one sale.
     * - **Paged list** (no invoice number): equality filters (`customerEntityId` via `whereIn`,
     *   `hasOutstandingBalance`) + a `createdAt` range, ordered `createdAt` then `saleId`
     *   descending so pagination is deterministic even when two sales share a timestamp. The
     *   cursor is that sort key of the previous page's last row.
     *
     * IMEI is never handled here (it isn't queryable on the sale doc); see [findSaleIdByImei].
     */
    override suspend fun querySales(query: SalesQuery): SalesPage = withContext(Dispatchers.IO) {
        val db = firestore()
        val col = db.collection(SALES)

        val invoiceNumber = query.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (invoiceNumber != null) {
            val docs = col.whereEqualTo("invoiceNumber", invoiceNumber).get().get().documents
            val rows = docs.map { it.toSaleSummary() }
                .filter { it.matchesClientSide(query) }
                .sortedByDescending { it.createdAtMillis }
            return@withContext SalesPage(rows, nextCursor = null)
        }

        var q: Query = col
        query.customerEntityIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            // whereIn caps at 30 ids; the caller (use case/VM) keeps the resolved set within that.
            q = if (ids.size == 1) q.whereEqualTo("customerEntityId", ids.first()) else q.whereIn("customerEntityId", ids)
        }
        if (query.onlyWithBalance) q = q.whereEqualTo("hasOutstandingBalance", true)
        query.dateFromMillis?.let { q = q.whereGreaterThanOrEqualTo("createdAt", Timestamp.of(Date(it))) }
        query.dateToMillis?.let { q = q.whereLessThanOrEqualTo("createdAt", Timestamp.of(Date(it))) }

        q = q.orderBy("createdAt", Query.Direction.DESCENDING).orderBy("saleId", Query.Direction.DESCENDING)
        // Reconstruct the boundary row's exact createdAt (seconds + full nanos) so paging never
        // truncates sub-millisecond precision and skips a same-millisecond sale at the boundary.
        query.cursor?.let { c ->
            q = q.startAfter(Timestamp.ofTimeSecondsAndNanos(c.createdAtMillis / 1000, c.createdAtNanos), c.saleId)
        }
        q = q.limit(query.pageSize)

        val docs = q.get().get().documents
        val rows = docs.map { it.toSaleSummary() }
        // A full page implies there may be more; a short page is the end (nextCursor = null).
        // Build the cursor from the last raw doc so its createdAt keeps nanosecond precision.
        val nextCursor = if (docs.size == query.pageSize) {
            docs.lastOrNull()?.let { doc ->
                val ts = doc.getTimestamp("createdAt")
                if (ts != null) SalesCursor(ts.toDate().time, doc.getString("saleId") ?: doc.id, ts.nanos) else null
            }
        } else {
            null
        }
        SalesPage(rows, nextCursor)
    }

    /** Direct `sales/{saleId}` read for the detail view (ticket #83); null if the doc is absent. */
    override suspend fun getSale(saleId: String): SaleDetail? = withContext(Dispatchers.IO) {
        val snap = firestore().collection(SALES).document(saleId).get().get()
        if (snap.exists()) snap.toSaleDetail() else null
    }

    /**
     * Resolves an IMEI to its sale (ticket #83) via the serial — `serials where imei == X →
     * serial.saleId`. The IMEI lives inside the sale's `lines` array of maps (not queryable), and
     * `imeiIndex/{imei}` is deleted when a unit sells, so the durable path is the serial itself.
     * Returns null if no serial carries the IMEI, or it carries no `saleId` (never sold).
     */
    override suspend fun findSaleIdByImei(imei: String): String? = withContext(Dispatchers.IO) {
        val trimmed = imei.trim()
        if (trimmed.isEmpty()) return@withContext null
        val serial = firestore().collection(SERIALS)
            .whereEqualTo("imei", trimmed).limit(1).get().get().documents.firstOrNull()
        serial?.getString("saleId")?.takeIf { it.isNotBlank() }
    }

    /**
     * Voids a sale (ticket #85). Desktop's Admin SDK bypasses Firestore rules, so it writes the
     * void request fields + `voidStatus: PENDING` directly onto the sale doc; `onSaleWrite`
     * edge-triggers on `voidRequestedAt` and runs the idempotent `voidSaleCore` (which re-verifies
     * admin server-side). Mirrors [retryInvoice]'s transport exactly.
     *
     * The nudge landing proves nothing (an Admin-SDK write succeeds whether or not a function is
     * listening), so we then wait for the CF to settle `voidStatus` to DONE or FAILED. On FAILED we
     * surface [SaleVoidFailedException] with the CF's reason (e.g. a re-used IMEI index). On DONE we
     * return; the caller reloads the now-VOIDED detail. A timeout with no settle means functions
     * aren't running — reported as a failure the admin can retry (the whole path is idempotent).
     */
    override suspend fun voidSale(saleId: String, reason: String): Unit = withContext(Dispatchers.IO) {
        val db = firestore()
        val ref = db.collection(SALES).document(saleId)
        ref.update(
            mapOf(
                "voidReason" to reason,
                "voidRequestedBy" to uid,
                "voidRequestedAt" to FieldValue.serverTimestamp(),
                "voidStatus" to VoidStatus.PENDING.name,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).get()

        val settled = withTimeoutOrNull(VOID_ACK_TIMEOUT) {
            observeVoidState(saleId).first { it.status == VoidStatus.DONE || it.status == VoidStatus.FAILED }
        } ?: throw SaleVoidFailedException(
            "The void is taking longer than expected. Reopen the sale to check — it may still complete.",
        )
        if (settled.status == VoidStatus.FAILED) throw SaleVoidFailedException(settled.error)
    }

    /** Live `voidStatus`/`voidError` for a sale (ticket #85) — the settle signal [voidSale] awaits. */
    private fun observeVoidState(saleId: String): Flow<SaleVoidState> = callbackFlow {
        val db = firestore()
        val registration = db.collection(SALES).document(saleId)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null && snapshot.exists() -> trySend(snapshot.toSaleVoidState())
                    else -> trySend(SaleVoidState())
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    /** Applies the non-invoice-number filters to a summary — used by the exact invoice search. */
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

    private fun DocumentSnapshot.toSaleVoidState(): SaleVoidState = SaleVoidState(
        status = VoidStatus.fromWire(getString("voidStatus")),
        reason = getString("voidReason"),
        voidedAtMillis = getTimestamp("voidedAt")?.toDate()?.time,
        error = getString("voidError"),
    )

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
            voidState = toSaleVoidState(),
        )
    }

    /** Maps the stored `lines` array of maps back to typed [SaleRecordLine]s. */
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
        // money is a decimal STRING, and Firestore orders strings lexicographically ("100.00" <
        // "90.00"), so an inequality query on balanceRemaining is silently wrong. Evaluate the
        // balance with Money here — where it's already computed — and filter on this boolean.
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
        // `createdAt` is the BUSINESS date — the day the sale happened, which is the day the
        // caller picked when old books are being entered. Every list, filter, cursor and the
        // invoice stamp already read this field as "when this happened", so backdating one field
        // dates the whole record consistently. The real keystroke time is kept as `enteredAt`.
        "createdAt" to Timestamp.of(Date(record.saleDate)),
        "enteredAt" to FieldValue.serverTimestamp(),
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

        /** Firestore's default is 5; two simultaneous same-unit sales can livelock within that,
         *  so we give the loser more (randomly-spaced) tries to reach a clean lost-race read. */
        const val MAX_TXN_ATTEMPTS = 12

        /** How long a Retry waits for `onSaleWrite` to flip the doc out of `FAILED`. Trigger
         *  latency is normally under a second and a cold start a few more; 20 s is generous
         *  enough that a slow start never reads as "not deployed". */
        val RETRY_ACK_TIMEOUT = 20.seconds

        /** How long a Void waits for `onSaleWrite` to settle `voidStatus`. Longer than a retry: the
         *  CF makes several HL round-trips (cancel + up to three refunds) on a possibly-cold Lambda,
         *  so 60 s keeps a slow-but-succeeding void from reading as "not deployed". */
        val VOID_ACK_TIMEOUT = 60.seconds
    }
}

/**
 * Blocks on a sale transaction's [ApiFuture], surfacing our [AlreadySoldException] (thrown
 * inside the txn body) instead of the [ExecutionException] wrapper — the Admin-SDK analog
 * of Android's `awaitTxn`.
 */
private fun <T> ApiFuture<T>.awaitSaleTxn(): T =
    try {
        get()
    } catch (e: ExecutionException) {
        val sold = generateSequence(e.cause) { it.cause }
            .filterIsInstance<AlreadySoldException>()
            .firstOrNull()
        if (sold != null) throw sold
        throw e
    }
