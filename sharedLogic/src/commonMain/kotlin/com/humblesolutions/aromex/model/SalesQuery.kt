package com.humblesolutions.aromex.model

/**
 * A filter + pagination request for the Sales History list (ticket #83). Sales are the first
 * **unbounded** collection in the app, so — unlike every cached-list screen — filtering runs
 * as Firestore queries and results are paged, never loaded whole and filtered client-side.
 *
 * Filters combine (AND). Design notes for the two traps this screen was built around:
 * - **[customerEntityIds]**, not a name: free-text customer search is resolved to entity ids
 *   client-side (over the bounded, cached `entities`) and the query filters on the id — the
 *   sale doc stores `customerEntityId`, never the name. A list keeps multi-match handling to
 *   the repository (Firestore `in`, ≤ 30 ids).
 * - **[imei]** is handled out-of-band: it can't be queried on the `sales` doc (it lives inside
 *   the `lines` array of maps) and `imeiIndex/{imei}` is deleted when a unit sells, so the
 *   repository resolves it via `serials where imei == X → serial.saleId → sales/{saleId}`.
 *   When [imei] is set the other filters/pagination don't apply — it targets one sale.
 * - **[onlyWithBalance]** filters on the denormalized `hasOutstandingBalance` boolean, never a
 *   string comparison of `balanceRemaining` (which Firestore would order lexicographically,
 *   making "100.00" < "90.00" — a silent bug).
 *
 * [dateFromMillis]/[dateToMillis] are an inclusive epoch-millis range on `createdAt`.
 */
data class SalesQuery(
    val customerEntityIds: List<String>? = null,
    val invoiceNumber: String? = null,
    val imei: String? = null,
    val dateFromMillis: Long? = null,
    val dateToMillis: Long? = null,
    val onlyWithBalance: Boolean = false,
    val cursor: SalesCursor? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 30
    }
}

/**
 * An opaque forward-only cursor into the newest-first sales list (ticket #83). Carries the
 * `orderBy(createdAt desc, saleId desc)` sort key of the last row of the previous page, so the
 * next page can `startAfter(...)` it. Kept as pure data (a Firestore timestamp's seconds+nanos
 * + id) rather than a platform `DocumentSnapshot` so `sharedLogic` stays free of Firestore
 * types; `saleId` is the tie-breaker for sales sharing a `createdAt`.
 *
 * [createdAtNanos] is the **full nanoseconds-of-second** component (0..999,999,999) of the
 * boundary row's `createdAt`. It's carried alongside [createdAtMillis] so the cursor reconstructs
 * the row's *exact* timestamp for `startAfter`: Firestore stores `createdAt` with nanosecond
 * precision, and truncating to millis can drop a row when two sales share the same millisecond
 * and the page boundary falls between them. Defaults to 0 for a whole-millisecond timestamp.
 */
data class SalesCursor(
    val createdAtMillis: Long,
    val saleId: String,
    val createdAtNanos: Int = 0,
)

/**
 * One page of the sales list (ticket #83): the rows plus the cursor to fetch the next page.
 * [nextCursor] is null when the last page has been reached (fewer rows than the page size).
 */
data class SalesPage(
    val sales: List<SaleSummary>,
    val nextCursor: SalesCursor?,
)
