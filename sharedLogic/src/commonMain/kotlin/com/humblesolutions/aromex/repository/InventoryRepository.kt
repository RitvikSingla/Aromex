package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.AddStockResult
import com.humblesolutions.aromex.model.CommissionInput
import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.PurchaseInput
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.ProductEdits
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialEdits
import com.humblesolutions.aromex.model.SerialStatus
import kotlinx.coroutines.flow.Flow

/**
 * The operational store for inventory — the client's Firebase (Firestore).
 * Implementations are per-platform (Android/iOS native SDK; Desktop Admin SDK) and
 * live in T3/T4. This interface is **Firebase-only** — inventory touches no Humble
 * Ledger. Permission enforcement lives in the use cases (not here), because Desktop's
 * Admin SDK bypasses Firestore rules.
 *
 * ### Race-safe transactional contract (implement identically on every platform — see
 * `docs/SCHEMA.md` Part 2). Adds/status/archives run as a **client-side Firestore
 * transaction** (no Cloud Function):
 *
 * - **addStock / addUnits** — one transaction:
 *   1. find-or-create `products/{skuKey}` (the id **is** the skuKey → atomic; no
 *      duplicate SKUs even under concurrent cashiers);
 *   2. for each unit: read `imeiIndex/{imei}` → if present, **abort the whole
 *      transaction** and throw [com.humblesolutions.aromex.model.DuplicateImeiException];
 *      else create `serials/{autoId}` + `imeiIndex/{imei}`. All-or-nothing.
 * - **Release the IMEI** whenever a unit leaves stock: `setSerialStatus(SOLD)` and
 *   `archiveSerial` **delete** `imeiIndex/{imei}` in the same transaction (in-stock-only
 *   uniqueness — a returned phone can be re-added later).
 * - **updateSerial** with a changed `imei` re-keys the index (delete old + create new)
 *   in one transaction.
 *
 * `serialId`/`productId` are stored **inside** their docs (== the doc key).
 */
interface InventoryRepository {
    /** Live stream of SKUs. When [includeArchived] is false, only `isActive == true`. */
    fun observeProducts(includeArchived: Boolean): Flow<List<Product>>

    /**
     * Live stream of in-stock units (`status == IN_STOCK && isActive == true`). Backs
     * per-SKU stock counts + drill-in (and Sales' pick-by-IMEI later). The count per SKU
     * is derived by grouping on `productId` **client-side** — never stored.
     */
    fun observeInStockSerials(): Flow<List<Serial>>

    /**
     * Adds stock: find-or-create the SKU for [skuKey] (from [product]'s attributes) and
     * create the [units]. Transactional per the contract above.
     * @throws com.humblesolutions.aromex.model.DuplicateImeiException if any unit's IMEI
     * is already held by an in-stock unit (nothing is written).
     */
    suspend fun addStock(skuKey: String, product: NewProduct, units: List<NewUnit>): AddStockResult

    /**
     * Adds [units] to an existing SKU [productId]. Same imeiIndex transaction as [addStock].
     * @throws com.humblesolutions.aromex.model.DuplicateImeiException on an in-stock IMEI clash.
     */
    suspend fun addUnits(productId: String, units: List<NewUnit>): List<String>

    /**
     * Adds a whole reviewed batch (one or more SKU [groups]) **and** its single [purchase]
     * record **and** any [commissions] the user confirmed at intake in **one Firestore
     * transaction** — either all the stock (products + serials + imeiIndex), the
     * `purchases/{id}` doc, and every `commissions/{id}` doc commit, or none do. This is the
     * ticket-#58 invariant, extended by #97: no in-stock unit can ever exist without its
     * purchase record *or without the commission obligation it created* — a second write that
     * could fail alone would eventually leave phones in the system and a forgotten debt.
     *
     * Each group is find-or-created by `skuKey` exactly like [addStock]/[addUnits] (a null
     * [StockBatchGroup.product] is the add-units-to-existing path — the product doc is not
     * rewritten). The batch is pre-capped
     * ([com.humblesolutions.aromex.util.InventoryLimits.SAFE_BATCH_CEILING]) so it always
     * fits under Firestore's ~500-write transaction limit — one transaction suffices.
     *
     * Each [CommissionInput] is written `PENDING` with `sourceBatchId` set to the purchase id;
     * the `onCommissionWrite` Cloud Function posts it to HL (accrue, then optional payout).
     * [commissions] is empty when no rule matched or every proposed line was skipped.
     *
     * @throws com.humblesolutions.aromex.model.DuplicateImeiException if any unit's IMEI is
     * already held by an in-stock unit (nothing is written).
     */
    suspend fun addStockBatchWithPurchase(
        groups: List<StockBatchGroup>,
        purchase: PurchaseInput,
        commissions: List<CommissionInput>,
    )

    /** Corrects a unit's details; a changed IMEI re-keys `imeiIndex` in one transaction. */
    suspend fun updateSerial(serialId: String, edits: SerialEdits)

    /** Sets a unit's stock status; leaving stock (SOLD) releases its `imeiIndex` entry. */
    suspend fun setSerialStatus(serialId: String, status: SerialStatus)

    /** Soft-archives a unit (`isActive = false`) and releases its `imeiIndex` entry. */
    suspend fun archiveSerial(serialId: String)

    /** Soft-archives a SKU (`isActive = false`). We never hard-delete. */
    suspend fun archiveProduct(productId: String)

    /** Edits a SKU's default selling price (the only mutable product field). Single-doc, not transactional. */
    suspend fun updateProduct(productId: String, edits: ProductEdits)

    /**
     * Advisory pre-check: returns true when [imei] (trimmed) is present in `imeiIndex` —
     * i.e., an in-stock unit already holds that IMEI. Used to give the ✓ button instant
     * feedback. **Not the transactional authority** — a race between this read and the
     * confirm-time [addStock]/[addUnits] write is still caught by [com.humblesolutions.aromex.model.DuplicateImeiException].
     */
    suspend fun isImeiInStock(imei: String): Boolean
}
