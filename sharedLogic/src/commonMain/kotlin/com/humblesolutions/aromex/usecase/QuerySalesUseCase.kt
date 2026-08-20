package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.repository.SalesRepository

/**
 * Reads a page of Sales History (ticket #83), gated on `sales` **VIEW** (VIEW or MANAGE both
 * read; the real gate on Desktop, whose Admin-SDK access bypasses Firestore rules — `CLAUDE.md`).
 *
 * Two query paths, mutually exclusive:
 * - **IMEI search** ([SalesQuery.imei] set): the IMEI can't be queried on the sale doc, so this
 *   resolves it through the serial — `findSaleIdByImei → getSale` — and returns a page of 0 or 1.
 *   The remaining filters are then applied to that single result client-side (there's nothing to
 *   page), so IMEI still combines with the date range and the balance toggle.
 * - **List** (no IMEI): delegates to [SalesRepository.querySales], which runs the filters as
 *   Firestore queries and pages via the cursor.
 */
class QuerySalesUseCase(
    private val repository: SalesRepository,
) {
    /**
     * @return one [SalesPage]; [SalesPage.nextCursor] is null on the last page (and always null
     *   for the IMEI path, which targets a single sale).
     * @throws PermissionDeniedException without `sales` VIEW.
     */
    suspend fun execute(session: com.humblesolutions.aromex.model.UserSession, query: SalesQuery): SalesPage {
        if (session.permissions.sales == PermissionLevel.NONE) {
            throw PermissionDeniedException("sales")
        }

        val imei = query.imei?.trim().orEmpty()
        if (imei.isNotEmpty()) {
            val saleId = repository.findSaleIdByImei(imei) ?: return SalesPage(emptyList(), null)
            val detail = repository.getSale(saleId) ?: return SalesPage(emptyList(), null)
            val summary = detail.toSummary()
            return SalesPage(listOf(summary).filter { it.matches(query) }, null)
        }

        return repository.querySales(query)
    }

    /** Applies the non-IMEI filters to a single resolved sale (the IMEI path has no server query). */
    private fun SaleSummary.matches(query: SalesQuery): Boolean {
        if (query.onlyWithBalance && !hasBalance()) return false
        query.dateFromMillis?.let { if (createdAtMillis < it) return false }
        query.dateToMillis?.let { if (createdAtMillis > it) return false }
        query.customerEntityIds?.let { if (customerEntityId !in it) return false }
        query.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!invoiceNumber.equals(it, ignoreCase = true)) return false
        }
        return true
    }

    /** True when this row carries an outstanding balance, judged with [com.humblesolutions.aromex.util.Money]. */
    private fun SaleSummary.hasBalance(): Boolean =
        !com.humblesolutions.aromex.util.Money.isZero(balanceRemaining)
}

/**
 * Projects a [com.humblesolutions.aromex.model.SaleDetail] into a [SaleSummary] row — used by the
 * IMEI path, which fetches the full sale but presents it in the same list as everything else.
 */
internal fun com.humblesolutions.aromex.model.SaleDetail.toSummary(): SaleSummary {
    val firstInventory = inventoryLines.firstOrNull()
    return SaleSummary(
        saleId = saleId,
        createdAtMillis = createdAtMillis,
        customerEntityId = customerEntityId,
        isWalkIn = isWalkIn,
        buyerName = buyerName,
        firstItemLabel = firstInventory?.label
            ?: (lines.firstOrNull() as? com.humblesolutions.aromex.model.SaleRecordLine.Custom)?.name
            ?: "",
        itemCount = lines.size,
        firstImei = firstInventory?.imei,
        itemLabels = lines.map {
            when (it) {
                is com.humblesolutions.aromex.model.SaleRecordLine.Inventory -> it.label
                is com.humblesolutions.aromex.model.SaleRecordLine.Custom -> it.name
            }
        },
        imeis = inventoryLines.map { it.imei },
        grandTotal = grandTotal,
        amountPaid = amountPaid,
        balanceRemaining = balanceRemaining,
        syncStatus = syncStatus,
        invoiceNumber = invoice.number,
        invoiceStatus = invoice.status,
    )
}
