package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.TrackingMode
import com.humblesolutions.aromex.repository.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firestore-backed [InventoryRepository] for the signed-in company — the Android native
 * SDK impl of the shared contract. Firebase-only (inventory touches no Humble Ledger).
 *
 * The adds/status/archives run as **client-side Firestore transactions** exactly per
 * `docs/SCHEMA.md` Part 2 (mirrored on iOS/Desktop):
 *  - **addStock/addUnits** — find-or-create `products/{skuKey}` (the doc id **is** the
 *    skuKey → atomic, no duplicate SKUs under concurrent cashiers); per unit, read
 *    `imeiIndex/{imei}` → if present abort the whole txn with [DuplicateImeiException],
 *    else write `serials/{autoId}` + `imeiIndex/{imei}` together.
 *  - **setSerialStatus(SOLD)/archiveSerial** — release the unit's `imeiIndex` entry in
 *    the same txn (in-stock-only uniqueness → a returned phone can be re-added later).
 *  - **updateSerial** with a changed imei re-keys the index (delete old + create new).
 *
 * `productId`/`serialId`/`attributeId` are stored **inside** their docs (== the doc key).
 */
class BackendInventoryRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : InventoryRepository {

    private val app get() = FirebaseAppFactory.get(context, config)
    private val db get() = Firebase.firestore(app)
    private fun currentUid(): String? = FirebaseAuth.getInstance(app).currentUser?.uid

    // ── Live reads (snapshot listener → Flow) ────────────────────────────────────

    override fun observeProducts(includeArchived: Boolean): Flow<List<Product>> = callbackFlow {
        val query: Query = if (includeArchived) {
            db.collection(PRODUCTS)
        } else {
            db.collection(PRODUCTS).whereEqualTo("isActive", true)
        }
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toProduct() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    override fun observeInStockSerials(): Flow<List<Serial>> = callbackFlow {
        // Backs per-SKU stock counts + drill-in; grouping on productId is done client-side.
        val query = db.collection(SERIALS)
            .whereEqualTo("status", SerialStatus.IN_STOCK.name)
            .whereEqualTo("isActive", true)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toSerial() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    // ── Transactional writes ─────────────────────────────────────────────────────

    override suspend fun addStock(
        skuKey: String,
        product: NewProduct,
        units: List<NewUnit>,
    ): AddStockResult = withContext(Dispatchers.IO) {
        val uid = currentUid()
        val productRef = db.collection(PRODUCTS).document(skuKey)
        // Pre-allocate serial refs (ids) outside the txn so the result is knowable; the
        // txn only reads/writes. Firestore requires all reads before any write.
        val serialRefs = units.map { db.collection(SERIALS).document() }
        val imeiRefs = units.map { db.collection(IMEI_INDEX).document(it.imei.trim()) }

        db.runTransaction { txn ->
            val productSnap = txn.get(productRef)
            // Read every imeiIndex up-front (all reads must precede writes in a txn).
            val existing = imeiRefs.mapIndexedNotNull { i, ref ->
                if (txn.get(ref).exists()) units[i].imei.trim() else null
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
        }.awaitTxn()

        AddStockResult(productId = skuKey, createdSerialIds = serialRefs.map { it.id })
    }

    override suspend fun addUnits(
        productId: String,
        units: List<NewUnit>,
    ): List<String> = withContext(Dispatchers.IO) {
        val uid = currentUid()
        val serialRefs = units.map { db.collection(SERIALS).document() }
        val imeiRefs = units.map { db.collection(IMEI_INDEX).document(it.imei.trim()) }

        db.runTransaction { txn ->
            val existing = imeiRefs.mapIndexedNotNull { i, ref ->
                if (txn.get(ref).exists()) units[i].imei.trim() else null
            }
            if (existing.isNotEmpty()) throw DuplicateImeiException(existing)
            units.forEachIndexed { i, unit ->
                txn.set(serialRefs[i], serialData(serialRefs[i].id, productId, unit, uid))
                txn.set(imeiRefs[i], imeiIndexData(unit.imei.trim(), serialRefs[i].id, productId))
            }
            null
        }.awaitTxn()

        serialRefs.map { it.id }
    }

    override suspend fun addStockBatchWithPurchase(
        groups: List<StockBatchGroup>,
        purchase: PurchaseInput,
        commissions: List<CommissionInput>,
    ): Unit = withContext(Dispatchers.IO) {
        val uid = currentUid()
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

        db.runTransaction { txn ->
            // ── Reads first ──────────────────────────────────────────────────────────
            val productExists = prepared.map { txn.get(it.productRef).exists() }
            val existing = prepared.flatMap { p ->
                p.imeiRefs.mapIndexedNotNull { i, ref ->
                    if (txn.get(ref).exists()) p.group.units[i].imei.trim() else null
                }
            }
            if (existing.isNotEmpty()) throw DuplicateImeiException(existing)

            // ── Writes ─────────────────────────────────────────────────────────────────
            prepared.forEachIndexed { gi, p ->
                val product = p.group.product
                if (!productExists[gi] && product != null) {
                    txn.set(p.productRef, productData(p.group.skuKey, product, uid))
                }
                p.group.units.forEachIndexed { i, unit ->
                    txn.set(p.serialRefs[i], serialData(p.serialRefs[i].id, p.group.skuKey, unit, uid))
                    txn.set(p.imeiRefs[i], imeiIndexData(unit.imei.trim(), p.serialRefs[i].id, p.group.skuKey))
                }
            }
            txn.set(purchaseRef, purchaseData(purchase, uid))
            commissions.forEachIndexed { i, c ->
                txn.set(commissionRefs[i], commissionData(commissionRefs[i].id, c, purchaseRef.id, uid))
            }
            null
        }.awaitTxn()
    }

    override suspend fun updateSerial(serialId: String, edits: SerialEdits): Unit =
        withContext(Dispatchers.IO) {
            val serialRef = db.collection(SERIALS).document(serialId)
            db.runTransaction { txn ->
                val snap = txn.get(serialRef)
                if (!snap.exists()) throw IllegalStateException("Unit not found: $serialId")
                val newImei = edits.imei?.trim()
                val oldImei = snap.getString("imei")
                val productId = snap.getString("productId").orEmpty()

                // A changed IMEI re-keys imeiIndex: reject if the new one is already in
                // stock, then release the old key + claim the new — all in this txn.
                // NOTE: T2's mobile rules pin `imei` immutable on a serial update, so this
                // branch is rejected on mobile by design — a mistyped IMEI is corrected by
                // void (archive → release index) + re-add (docs/SCHEMA.md). The test UI
                // therefore never drives an in-place IMEI edit; the branch honors the shared
                // contract for the rule-bypassing Desktop impl (T4).
                if (newImei != null && newImei != oldImei) {
                    val newIndexRef = db.collection(IMEI_INDEX).document(newImei)
                    if (txn.get(newIndexRef).exists()) throw DuplicateImeiException(listOf(newImei))
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
            }.awaitTxn()
        }

    override suspend fun setSerialStatus(serialId: String, status: SerialStatus): Unit =
        withContext(Dispatchers.IO) {
            val serialRef = db.collection(SERIALS).document(serialId)
            db.runTransaction { txn ->
                val snap = txn.get(serialRef)
                if (!snap.exists()) throw IllegalStateException("Unit not found: $serialId")
                val imei = snap.getString("imei")
                val wasInStock = snap.getString("status") == SerialStatus.IN_STOCK.name
                val productId = snap.getString("productId").orEmpty()

                // All reads must precede all writes in a Firestore transaction: pre-read the
                // index for the return-to-stock re-claim BEFORE touching the serial doc.
                val indexRef = imei?.takeIf { it.isNotBlank() }?.let { db.collection(IMEI_INDEX).document(it) }
                val indexTaken = if (status == SerialStatus.IN_STOCK && !wasInStock && indexRef != null) {
                    txn.get(indexRef).exists()
                } else {
                    false
                }

                txn.update(
                    serialRef,
                    mapOf("status" to status.name, "updatedAt" to FieldValue.serverTimestamp()),
                )
                if (indexRef == null) return@runTransaction null
                if (status == SerialStatus.IN_STOCK) {
                    // Returning to stock re-claims the index (guard against a clash).
                    if (indexTaken) throw DuplicateImeiException(listOf(imei!!))
                    txn.set(indexRef, imeiIndexData(imei!!, serialId, productId))
                } else {
                    // Leaving stock (SOLD/RESERVED) releases the in-stock guard.
                    txn.delete(indexRef)
                }
                null
            }.awaitTxn()
        }

    override suspend fun archiveSerial(serialId: String): Unit = withContext(Dispatchers.IO) {
        val serialRef = db.collection(SERIALS).document(serialId)
        db.runTransaction { txn ->
            val snap = txn.get(serialRef)
            if (!snap.exists()) throw IllegalStateException("Unit not found: $serialId")
            val imei = snap.getString("imei")
            txn.update(
                serialRef,
                mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
            )
            if (!imei.isNullOrBlank()) txn.delete(db.collection(IMEI_INDEX).document(imei))
            null
        }.awaitTxn()
    }

    override suspend fun archiveProduct(productId: String): Unit = withContext(Dispatchers.IO) {
        db.collection(PRODUCTS).document(productId).update(
            mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    override suspend fun updateProduct(productId: String, edits: ProductEdits): Unit =
        withContext(Dispatchers.IO) {
            val updates = mutableMapOf<String, Any?>("updatedAt" to FieldValue.serverTimestamp())
            edits.defaultSellingPrice?.let { updates["defaultSellingPrice"] = it }
            db.collection(PRODUCTS).document(productId).update(updates).await()
        }

    override suspend fun isImeiInStock(imei: String): Boolean = withContext(Dispatchers.IO) {
        db.collection(IMEI_INDEX).document(imei.trim()).get().await().exists()
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

    private fun serialData(serialId: String, productId: String, unit: NewUnit, uid: String?): Map<String, Any?> =
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
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    private fun imeiIndexData(imei: String, serialId: String, productId: String): Map<String, Any?> =
        mapOf("imei" to imei, "serialId" to serialId, "productId" to productId)

    /** The `purchases/{id}` doc, written PENDING inside the inventory txn (the `onPurchaseWrite` CF posts to HL). */
    private fun purchaseData(purchase: PurchaseInput, uid: String?): Map<String, Any?> =
        mapOf(
            "partyEntityId" to purchase.partyEntityId,
            "totalCost" to purchase.totalCost, // decimal String — never a Double
            "cashPaid" to purchase.cashPaid,
            "bankPaid" to purchase.bankPaid,
            "syncStatus" to HlSyncStatus.PENDING.name,
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    /**
     * The `commissions/{id}` doc written in the batch transaction (ticket #97). PENDING with
     * `sourceBatchId` = the purchase id; the `onCommissionWrite` CF owns `syncStatus`/`hl*`.
     * `paidNow` is `{ method }` or absent (accrue only). Money fields are decimal Strings.
     */
    private fun commissionData(
        commissionId: String,
        c: CommissionInput,
        sourceBatchId: String,
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
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

    private fun attributeRefData(ref: AttributeRef): Map<String, Any?> =
        mapOf("attributeId" to ref.attributeId, "name" to ref.name)

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
 * Awaits a Firestore transaction Task, unwrapping the SDK's `FirebaseFirestoreException`
 * whose cause is our [DuplicateImeiException] (thrown inside the txn body) so callers see
 * the domain exception, not the wrapper.
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTxn(): T =
    try {
        await()
    } catch (e: FirebaseFirestoreException) {
        (e.cause as? DuplicateImeiException)?.let { throw it }
        throw e
    }
