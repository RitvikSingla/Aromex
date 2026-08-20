package com.humblesolutions.aromex.data

import com.google.api.core.ApiFuture
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2CredentialsWithRefresh
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.Transaction
import com.humblesolutions.aromex.model.AddStockResult
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.CommissionInput
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.DuplicateImeiException
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.ProductEdits
import com.humblesolutions.aromex.model.PurchaseInput
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialEdits
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.StockBatchStatus
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.TrackingMode
import com.humblesolutions.aromex.repository.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.ExecutionException

/**
 * Firestore-backed [InventoryRepository] for Desktop, using the **Admin SDK**
 * (`google-cloud-firestore`) authenticated with a short-lived, datastore-scoped OAuth
 * token brokered by the gateway ([FirestoreTokenBroker]) — the SA key never reaches the
 * device. Firebase-only (inventory touches no Humble Ledger).
 *
 * ⚠️ Because this authenticates as the company's service account, these calls **bypass
 * Firestore security rules**. Permission enforcement therefore lives entirely in the
 * shared inventory use cases — every write must go through them, so they are the ONLY
 * line of enforcement on Desktop (T2's rules are a mobile backstop only).
 *
 * The adds/status/archives run as **client-side Admin-SDK transactions** exactly per
 * `docs/SCHEMA.md` Part 2 (mirrored from Android's `BackendInventoryRepository`):
 *  - **addStock/addUnits** — find-or-create `products/{skuKey}` (the doc id **is** the
 *    skuKey → atomic, no duplicate SKUs under concurrent cashiers); per unit, read
 *    `imeiIndex/{imei}` → if present abort the whole txn with [DuplicateImeiException],
 *    else write `serials/{autoId}` + `imeiIndex/{imei}` together.
 *  - **setSerialStatus(SOLD)/archiveSerial** — release the unit's `imeiIndex` entry in
 *    the same txn (in-stock-only uniqueness → a returned phone can be re-added later).
 *  - **updateSerial** with a changed imei re-keys the index (delete old + create new).
 *
 * Firestore requires **all reads before any writes** in a transaction; the ordering here
 * mirrors the platform-agnostic contract already implemented on mobile.
 *
 * All calls hop to [Dispatchers.IO] — `google-cloud-firestore` is blocking gRPC.
 */
class BackendInventoryRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
    private val uid: String,
) : InventoryRepository {

    private val clientLock = Mutex()
    @Volatile private var cached: Firestore? = null

    private suspend fun firestore(): Firestore = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        clientLock.withLock {
            cached?.let { return@withLock it }
            // Seed with a current token, then let the Admin SDK re-broker via the handler
            // when it expires. Firestore is built ONCE and reused, so a long-lived snapshot
            // listener keeps working past the ~1h token TTL instead of failing UNAUTHENTICATED.
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

    /** Nominal token lifetime hint so the Admin SDK refreshes ahead of the real ~1h expiry. */
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

    // ── Live reads (snapshot listener → Flow) ────────────────────────────────────

    override fun observeProducts(includeArchived: Boolean): Flow<List<Product>> = callbackFlow {
        val firestore = firestore()
        val query: Query = if (includeArchived) {
            firestore.collection(PRODUCTS)
        } else {
            firestore.collection(PRODUCTS).whereEqualTo("isActive", true)
        }
        val registration = query.addSnapshotListener(
            EventListener<QuerySnapshot> { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@EventListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toProduct() }.orEmpty())
            },
        )
        awaitClose { registration.remove() }
    }

    override fun observeInStockSerials(): Flow<List<Serial>> = callbackFlow {
        // Backs per-SKU stock counts + drill-in; grouping on productId is done client-side.
        val firestore = firestore()
        val registration = firestore.collection(SERIALS)
            .whereEqualTo("status", SerialStatus.IN_STOCK.name)
            .whereEqualTo("isActive", true)
            .addSnapshotListener(
                EventListener<QuerySnapshot> { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@EventListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toSerial() }.orEmpty())
                },
            )
        awaitClose { registration.remove() }
    }

    // ── Transactional writes ─────────────────────────────────────────────────────

    override suspend fun addStock(
        skuKey: String,
        product: NewProduct,
        units: List<NewUnit>,
    ): AddStockResult = withContext(Dispatchers.IO) {
        val db = firestore()
        val productRef = db.collection(PRODUCTS).document(skuKey)
        // Pre-allocate serial refs (ids) outside the txn so the result is knowable; the
        // txn only reads/writes. Firestore requires all reads before any write.
        val serialRefs = units.map { db.collection(SERIALS).document() }
        val imeiRefs = units.map { db.collection(IMEI_INDEX).document(it.imei.trim()) }

        db.runTransaction(
            Transaction.Function<Void?> { txn ->
                val productSnap = txn.get(productRef).get()
                // Read every imeiIndex up-front (all reads must precede writes in a txn).
                val existing = imeiRefs.mapIndexedNotNull { i, ref ->
                    if (txn.get(ref).get().exists()) units[i].imei.trim() else null
                }
                if (existing.isNotEmpty()) throw DuplicateImeiException(existing)

                if (!productSnap.exists()) {
                    txn.set(productRef, productData(skuKey, product, uid))
                }
                units.forEachIndexed { i, unit ->
                    txn.set(serialRefs[i], serialData(serialRefs[i].id, skuKey, unit, uid))
                    txn.set(imeiRefs[i], imeiIndexData(unit.imei.trim(), serialRefs[i].id, skuKey))
                }
                null
            },
        ).awaitTxn()

        AddStockResult(productId = skuKey, createdSerialIds = serialRefs.map { it.id })
    }

    override suspend fun addUnits(
        productId: String,
        units: List<NewUnit>,
    ): List<String> = withContext(Dispatchers.IO) {
        val db = firestore()
        val serialRefs = units.map { db.collection(SERIALS).document() }
        val imeiRefs = units.map { db.collection(IMEI_INDEX).document(it.imei.trim()) }

        db.runTransaction(
            Transaction.Function<Void?> { txn ->
                val existing = imeiRefs.mapIndexedNotNull { i, ref ->
                    if (txn.get(ref).get().exists()) units[i].imei.trim() else null
                }
                if (existing.isNotEmpty()) throw DuplicateImeiException(existing)
                units.forEachIndexed { i, unit ->
                    txn.set(serialRefs[i], serialData(serialRefs[i].id, productId, unit, uid))
                    txn.set(imeiRefs[i], imeiIndexData(unit.imei.trim(), serialRefs[i].id, productId))
                }
                null
            },
        ).awaitTxn()

        serialRefs.map { it.id }
    }

    override suspend fun addStockBatchWithPurchase(
        groups: List<StockBatchGroup>,
        purchase: PurchaseInput,
        commissions: List<CommissionInput>,
    ): Unit = withContext(Dispatchers.IO) {
        val db = firestore()
        // Pre-allocate every ref outside the txn so the body only reads then writes
        // (Firestore requires all reads before any write). The whole batch + the purchase
        // doc + every commission doc go in ONE transaction: all-or-nothing (ticket #97 —
        // never stock without its purchase record or its commission obligation).
        val prepared = groups.map { group ->
            PreparedGroup(
                group = group,
                productRef = db.collection(PRODUCTS).document(group.skuKey),
                serialRefs = group.units.map { db.collection(SERIALS).document() },
                imeiRefs = group.units.map { db.collection(IMEI_INDEX).document(it.imei.trim()) },
            )
        }
        val purchaseRef = db.collection(PURCHASES).document()
        val commissionRefs = commissions.map { db.collection(COMMISSIONS).document() }

        db.runTransaction(
            Transaction.Function<Void?> { txn ->
                // ── Reads first ──────────────────────────────────────────────────────
                val productExists = prepared.map { txn.get(it.productRef).get().exists() }
                val existing = prepared.flatMap { p ->
                    p.imeiRefs.mapIndexedNotNull { i, ref ->
                        if (txn.get(ref).get().exists()) p.group.units[i].imei.trim() else null
                    }
                }
                if (existing.isNotEmpty()) throw DuplicateImeiException(existing)

                // ── Writes ───────────────────────────────────────────────────────────
                prepared.forEachIndexed { gi, p ->
                    val product = p.group.product
                    if (!productExists[gi] && product != null) {
                        txn.set(p.productRef, productData(p.group.skuKey, product, uid))
                    }
                    p.group.units.forEachIndexed { i, unit ->
                        txn.set(
                            p.serialRefs[i],
                            serialData(p.serialRefs[i].id, p.group.skuKey, unit, uid, purchaseRef.id),
                        )
                        txn.set(p.imeiRefs[i], imeiIndexData(unit.imei.trim(), p.serialRefs[i].id, p.group.skuKey))
                    }
                }
                txn.set(purchaseRef, purchaseData(purchase, groups.sumOf { it.units.size }, uid))
                commissions.forEachIndexed { i, c ->
                    txn.set(
                        commissionRefs[i],
                        commissionData(commissionRefs[i].id, c, purchaseRef.id, purchase.purchaseDate, uid),
                    )
                }
                null
            },
        ).awaitTxn()
    }

    override suspend fun updateSerial(serialId: String, edits: SerialEdits): Unit =
        withContext(Dispatchers.IO) {
            val db = firestore()
            val serialRef = db.collection(SERIALS).document(serialId)
            db.runTransaction(
                Transaction.Function<Void?> { txn ->
                    val snap = txn.get(serialRef).get()
                    if (!snap.exists()) throw IllegalStateException("Unit not found: $serialId")
                    val newImei = edits.imei?.trim()
                    val oldImei = snap.getString("imei")
                    val productId = snap.getString("productId").orEmpty()

                    // A changed IMEI re-keys imeiIndex: reject if the new one is already in
                    // stock, then release the old key + claim the new — all in this txn.
                    // T2's mobile rules pin `imei` immutable on a serial update, so the
                    // mobile test UI never drives this branch; on Desktop rules are bypassed,
                    // so this honors the shared contract for a rule-bypassing correction.
                    if (newImei != null && newImei != oldImei) {
                        val newIndexRef = db.collection(IMEI_INDEX).document(newImei)
                        if (txn.get(newIndexRef).get().exists()) throw DuplicateImeiException(listOf(newImei))
                        if (!oldImei.isNullOrBlank()) {
                            txn.delete(db.collection(IMEI_INDEX).document(oldImei))
                        }
                        txn.set(newIndexRef, imeiIndexData(newImei, serialId, productId))
                    }

                    val updates = mutableMapOf<String, Any?>("updatedAt" to FieldValue.serverTimestamp())
                    edits.cost?.let { updates["cost"] = it }
                    edits.condition?.let { updates["condition"] = it.name }
                    edits.location?.let { updates["location"] = attributeRefData(it) }
                    newImei?.let { updates["imei"] = it }
                    txn.update(serialRef, updates)
                    null
                },
            ).awaitTxn()
        }

    override suspend fun setSerialStatus(serialId: String, status: SerialStatus): Unit =
        withContext(Dispatchers.IO) {
            val db = firestore()
            val serialRef = db.collection(SERIALS).document(serialId)
            db.runTransaction(
                Transaction.Function<Void?> { txn ->
                    val snap = txn.get(serialRef).get()
                    if (!snap.exists()) throw IllegalStateException("Unit not found: $serialId")
                    val imei = snap.getString("imei")
                    val wasInStock = snap.getString("status") == SerialStatus.IN_STOCK.name
                    val productId = snap.getString("productId").orEmpty()

                    // All reads must precede all writes in a Firestore transaction: pre-read the
                    // index for the return-to-stock re-claim BEFORE touching the serial doc.
                    val indexRef = imei?.takeIf { it.isNotBlank() }
                        ?.let { db.collection(IMEI_INDEX).document(it) }
                    val indexTaken = if (status == SerialStatus.IN_STOCK && !wasInStock && indexRef != null) {
                        txn.get(indexRef).get().exists()
                    } else {
                        false
                    }

                    txn.update(
                        serialRef,
                        mapOf("status" to status.name, "updatedAt" to FieldValue.serverTimestamp()),
                    )
                    if (indexRef != null) {
                        if (status == SerialStatus.IN_STOCK) {
                            // Returning to stock re-claims the index (guard against a clash).
                            if (indexTaken) throw DuplicateImeiException(listOf(imei))
                            txn.set(indexRef, imeiIndexData(imei, serialId, productId))
                        } else {
                            // Leaving stock (SOLD/RESERVED) releases the in-stock guard.
                            txn.delete(indexRef)
                        }
                    }
                    null
                },
            ).awaitTxn()
        }

    override suspend fun archiveSerial(serialId: String): Unit = withContext(Dispatchers.IO) {
        val db = firestore()
        val serialRef = db.collection(SERIALS).document(serialId)
        db.runTransaction(
            Transaction.Function<Void?> { txn ->
                val snap = txn.get(serialRef).get()
                if (!snap.exists()) throw IllegalStateException("Unit not found: $serialId")
                val imei = snap.getString("imei")
                txn.update(
                    serialRef,
                    mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
                )
                if (!imei.isNullOrBlank()) txn.delete(db.collection(IMEI_INDEX).document(imei))
                null
            },
        ).awaitTxn()
    }

    override suspend fun archiveProduct(productId: String): Unit = withContext(Dispatchers.IO) {
        firestore().collection(PRODUCTS).document(productId).update(
            mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
        ).get()
    }

    override suspend fun updateProduct(productId: String, edits: ProductEdits): Unit =
        withContext(Dispatchers.IO) {
            val updates = mutableMapOf<String, Any?>("updatedAt" to FieldValue.serverTimestamp())
            edits.defaultSellingPrice?.let { updates["defaultSellingPrice"] = it }
            firestore().collection(PRODUCTS).document(productId).update(updates).get()
        }

    override suspend fun isImeiInStock(imei: String): Boolean = withContext(Dispatchers.IO) {
        firestore().collection(IMEI_INDEX).document(imei.trim()).get().get().exists()
    }

    /**
     * Close the cached Admin-SDK Firestore client (releases its gRPC channel). Call on
     * ViewModel teardown — google-cloud-firestore clients are not GC'd cheaply, so a new
     * one per screen-open would otherwise leak a channel each time.
     */
    fun close() {
        cached?.let { runCatching { it.close() } }
        cached = null
    }

    // ── Doc builders ─────────────────────────────────────────────────────────────

    private fun productData(skuKey: String, product: NewProduct, uid: String?): Map<String, Any?> =
        mapOf(
            "productId" to skuKey,
            "trackingMode" to product.trackingMode.name,
            "attributes" to product.attributes.entries.associate { (type, ref) ->
                type.wire to attributeRefData(ref)
            },
            "defaultSellingPrice" to product.defaultSellingPrice,
            "isActive" to true,
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    private fun serialData(
        serialId: String,
        productId: String,
        unit: NewUnit,
        uid: String?,
        // The batch that brought this unit in (ticket #106). Null on the two direct add paths,
        // which book no purchase. A reversal finds its stock by this field and nothing else, so
        // it is written at intake — there is no way to reconstruct it afterwards.
        purchaseId: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "serialId" to serialId,
            "productId" to productId,
            "imei" to unit.imei.trim(),
            "cost" to unit.cost,
            "condition" to unit.condition.name,
            "status" to SerialStatus.IN_STOCK.name,
            "location" to attributeRefData(unit.location),
            "isActive" to true,
            "saleId" to null,
            "purchaseId" to purchaseId,
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    private fun imeiIndexData(imei: String, serialId: String, productId: String): Map<String, Any?> =
        mapOf("imei" to imei, "serialId" to serialId, "productId" to productId)

    /** The `purchases/{id}` doc, written PENDING inside the inventory txn (the `onPurchaseWrite` CF posts to HL). */
    private fun purchaseData(purchase: PurchaseInput, unitCount: Int, uid: String?): Map<String, Any?> =
        mapOf(
            "partyEntityId" to purchase.partyEntityId,
            "totalCost" to purchase.totalCost, // decimal String — never a Double
            "cashPaid" to purchase.cashPaid,
            "bankPaid" to purchase.bankPaid,
            // How many phones this batch brought in, recorded now. Counting serials later would
            // report a shrinking batch as units sell, and it is a fact about the past.
            "unitCount" to unitCount,
            "status" to StockBatchStatus.ACTIVE.name,
            "syncStatus" to HlSyncStatus.PENDING.name,
            "createdBy" to uid,
            // The BUSINESS date — the day the stock was bought, which the caller picks when old
            // books are being entered. Stock History orders and filters on it, and the ledger
            // posting takes its accounting date from it. `enteredAt` keeps the keystroke time.
            "createdAt" to Timestamp.of(Date(purchase.purchaseDate)),
            "enteredAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    private fun attributeRefData(ref: AttributeRef): Map<String, Any?> =
        mapOf("attributeId" to ref.attributeId, "name" to ref.name)

    /**
     * The `commissions/{id}` doc written in the batch transaction (ticket #97). Written PENDING
     * with `sourceBatchId` = the purchase id; the `onCommissionWrite` Cloud Function owns
     * `syncStatus`/`hl*` from there. `paidNow` is `{ method }` or absent (accrue only). All money
     * fields are decimal Strings — never a Double.
     */
    private fun commissionData(
        commissionId: String,
        c: CommissionInput,
        sourceBatchId: String,
        businessDate: Long,
        uid: String?,
    ): Map<String, Any?> =
        mapOf(
            "commissionId" to commissionId,
            "payeeEntityId" to c.payeeEntityId,
            "locationAttributeId" to c.locationAttributeId,
            "ruleId" to c.ruleId,
            "unitCount" to c.unitCount,
            "basisAmount" to c.basisAmount,
            "amount" to c.amount,
            "paidCash" to c.paidCash,
            "paidBank" to c.paidBank,
            "sourceBatchId" to sourceBatchId,
            "syncStatus" to HlSyncStatus.PENDING.name,
            "createdBy" to uid,
            // The batch's business date, so a backdated intake's commission is owed from the day
            // the stock actually arrived rather than the day it was keyed in.
            "createdAt" to Timestamp.of(Date(businessDate)),
            "enteredAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    // ── Mapping ──────────────────────────────────────────────────────────────────

    private fun DocumentSnapshot.toProduct(): Product? {
        if (!exists()) return null
        @Suppress("UNCHECKED_CAST")
        val rawAttrs = get("attributes") as? Map<String, Any?> ?: emptyMap()
        val attributes = rawAttrs.mapNotNull { (key, value) ->
            val type = AttributeType.fromWire(key) ?: return@mapNotNull null
            val ref = value as? Map<*, *> ?: return@mapNotNull null
            type to AttributeRef(
                attributeId = ref["attributeId"] as? String ?: "",
                name = ref["name"] as? String ?: "",
            )
        }.toMap()
        return Product(
            productId = getString("productId") ?: id,
            trackingMode = TrackingMode.fromWire(getString("trackingMode") ?: "") ?: TrackingMode.SERIALIZED,
            attributes = attributes,
            defaultSellingPrice = getString("defaultSellingPrice") ?: "0",
            isActive = getBoolean("isActive") ?: true,
        )
    }

    private fun DocumentSnapshot.toSerial(): Serial? {
        if (!exists()) return null
        val loc = get("location") as? Map<*, *>
        return Serial(
            serialId = getString("serialId") ?: id,
            productId = getString("productId").orEmpty(),
            imei = getString("imei").orEmpty(),
            cost = getString("cost") ?: "0",
            condition = Condition.fromWire(getString("condition") ?: "") ?: Condition.NEW,
            status = SerialStatus.fromWire(getString("status") ?: "") ?: SerialStatus.IN_STOCK,
            location = AttributeRef(
                attributeId = loc?.get("attributeId") as? String ?: "",
                name = loc?.get("name") as? String ?: "",
            ),
            isActive = getBoolean("isActive") ?: true,
            saleId = getString("saleId"),
            purchaseId = getString("purchaseId"),
        )
    }

    /** Pre-allocated refs for one SKU group, so the transaction body only reads then writes. */
    private data class PreparedGroup(
        val group: StockBatchGroup,
        val productRef: DocumentReference,
        val serialRefs: List<DocumentReference>,
        val imeiRefs: List<DocumentReference>,
    )

    private companion object {
        const val PRODUCTS = "products"
        const val SERIALS = "serials"
        const val IMEI_INDEX = "imeiIndex"
        const val PURCHASES = "purchases"
        const val COMMISSIONS = "commissions"
    }
}

/**
 * Blocks on a transaction's [ApiFuture], unwrapping the [ExecutionException] whose cause
 * is our [DuplicateImeiException] (thrown inside the txn body) so callers see the domain
 * exception, not the wrapper — the Admin-SDK analog of Android's `awaitTxn`.
 */
private fun <T> ApiFuture<T>.awaitTxn(): T =
    try {
        get()
    } catch (e: ExecutionException) {
        val dup = generateSequence(e.cause) { it.cause }
            .filterIsInstance<DuplicateImeiException>()
            .firstOrNull()
        if (dup != null) throw dup
        throw e
    }
