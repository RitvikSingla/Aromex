package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.SalesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A [UserSession] with the given `sales` scope, role + tax config (GST 5% by default). */
internal fun saleSession(
    sales: PermissionLevel = PermissionLevel.MANAGE,
    tax: TaxConfig = TaxConfig(gstEnabled = true, gstRate = "0.05"),
    role: UserRole = UserRole.MEMBER,
): UserSession = UserSession(
    uid = "u1",
    email = "u@test",
    displayName = "U",
    role = role,
    permissions = Permissions(sales = sales),
    companyId = "c1",
    hlCompanyId = "hl1",
    currency = "CAD",
    tax = tax,
    isActive = true,
)

/**
 * Fake [SalesRepository] that records what it was handed. When [soldImei] is set, it throws
 * [AlreadySoldException] to simulate a race loss instead of recording.
 */
internal class FakeSalesRepository(
    private val soldImei: String? = null,
    /** Page returned by [querySales]; the test can swap it to assert paging/filters. */
    var page: SalesPage = SalesPage(emptyList(), null),
    /** Sales keyed by id for [getSale]. */
    var salesById: Map<String, SaleDetail> = emptyMap(),
    /** IMEI → saleId for [findSaleIdByImei]. */
    var imeiToSaleId: Map<String, String> = emptyMap(),
) : SalesRepository {
    val recorded = mutableListOf<SaleRecord>()

    /** The last query [querySales] received — lets a test assert the filters/cursor it built. */
    var lastQuery: SalesQuery? = null
    var querySalesCalls = 0

    override suspend fun recordSale(record: SaleRecord): String {
        soldImei?.let { throw AlreadySoldException(it) }
        recorded += record
        return "sale-1"
    }

    override fun observeSaleInvoice(saleId: String): Flow<SaleInvoice> = flowOf(SaleInvoice())

    override suspend fun retryInvoice(saleId: String): SaleInvoice = SaleInvoice()

    override suspend fun querySales(query: SalesQuery): SalesPage {
        lastQuery = query
        querySalesCalls++
        return page
    }

    override suspend fun getSale(saleId: String): SaleDetail? = salesById[saleId]

    override suspend fun findSaleIdByImei(imei: String): String? = imeiToSaleId[imei.trim()]

    /** Records the (saleId, reason) pairs [voidSale] was handed, so a test can assert delegation. */
    val voided = mutableListOf<Pair<String, String>>()

    override suspend fun voidSale(saleId: String, reason: String) {
        voided += saleId to reason
    }
}
