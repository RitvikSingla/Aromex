package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.AddStockResult
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.CommissionInput
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.ProductEdits
import com.humblesolutions.aromex.model.PurchaseInput
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialEdits
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.AttributeRepository
import com.humblesolutions.aromex.repository.InventoryRepository
import com.humblesolutions.aromex.util.AttributeName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Builds a UserSession whose only interesting field is the `inventory` scope. */
internal fun sessionWith(inventory: PermissionLevel): UserSession = UserSession(
    uid = "u1",
    email = "u@test",
    displayName = "U",
    role = UserRole.MEMBER,
    permissions = Permissions(inventory = inventory),
    companyId = "c1",
    hlCompanyId = "hl1",
    currency = "CAD",
    isActive = true,
)

/** A complete, valid set of the 5 SKU-defining attributes. */
internal fun skuAttributes(
    brand: String = "b1",
    model: String = "m1",
    capacity: String = "cap1",
    color: String = "col1",
    carrier: String = "car1",
): Map<AttributeType, AttributeRef> = mapOf(
    AttributeType.BRAND to AttributeRef(brand, "Apple"),
    AttributeType.MODEL to AttributeRef(model, "iPhone 15"),
    AttributeType.CAPACITY to AttributeRef(capacity, "128 GB"),
    AttributeType.COLOR to AttributeRef(color, "Pink"),
    AttributeType.CARRIER to AttributeRef(carrier, "Unlocked"),
)

/** A valid unit for add-stock tests. */
internal fun validUnit(
    imei: String = "356938035643809",
    cost: String = "540.00",
    location: AttributeRef = AttributeRef("loc1", "Warehouse A"),
): NewUnit = NewUnit(imei = imei, cost = cost, condition = Condition.NEW, location = location)

/** Records repository calls for use-case tests; no real backend. */
internal class FakeInventoryRepository : InventoryRepository {
    data class AddStockCall(val skuKey: String, val product: NewProduct, val units: List<NewUnit>)
    data class BatchPurchaseCall(
        val groups: List<StockBatchGroup>,
        val purchase: PurchaseInput,
        val commissions: List<CommissionInput>,
    )

    val addStockCalls = mutableListOf<AddStockCall>()
    val addUnitsCalls = mutableListOf<Pair<String, List<NewUnit>>>()
    val batchPurchaseCalls = mutableListOf<BatchPurchaseCall>()
    val updatedSerials = mutableListOf<Pair<String, SerialEdits>>()
    val statusChanges = mutableListOf<Pair<String, SerialStatus>>()
    val archivedSerials = mutableListOf<String>()
    val archivedProducts = mutableListOf<String>()
    val updatedProducts = mutableListOf<Pair<String, ProductEdits>>()

    val products = MutableStateFlow<List<Product>>(emptyList())
    val inStockSerials = MutableStateFlow<List<Serial>>(emptyList())
    var lastIncludeArchived: Boolean? = null

    override fun observeProducts(includeArchived: Boolean): Flow<List<Product>> {
        lastIncludeArchived = includeArchived
        return products
    }

    override fun observeInStockSerials(): Flow<List<Serial>> = inStockSerials

    override suspend fun addStock(skuKey: String, product: NewProduct, units: List<NewUnit>): AddStockResult {
        addStockCalls += AddStockCall(skuKey, product, units)
        return AddStockResult(skuKey, units.indices.map { "serial-$it" })
    }

    override suspend fun addUnits(productId: String, units: List<NewUnit>): List<String> {
        addUnitsCalls += productId to units
        return units.indices.map { "serial-$it" }
    }

    override suspend fun addStockBatchWithPurchase(
        groups: List<StockBatchGroup>,
        purchase: PurchaseInput,
        commissions: List<CommissionInput>,
    ) {
        batchPurchaseCalls += BatchPurchaseCall(groups, purchase, commissions)
    }

    override suspend fun updateSerial(serialId: String, edits: SerialEdits) {
        updatedSerials += serialId to edits
    }

    override suspend fun setSerialStatus(serialId: String, status: SerialStatus) {
        statusChanges += serialId to status
    }

    override suspend fun archiveSerial(serialId: String) {
        archivedSerials += serialId
    }

    override suspend fun archiveProduct(productId: String) {
        archivedProducts += productId
    }

    override suspend fun updateProduct(productId: String, edits: ProductEdits) {
        updatedProducts += productId to edits
    }

    var imeiInStock: Set<String> = emptySet()
    override suspend fun isImeiInStock(imei: String): Boolean = imei.trim() in imeiInStock
}

/**
 * Records add-attribute calls and mirrors the real **case-insensitive dedupe** contract:
 * a matching `(type, parentId, matchKey)` returns the existing id; otherwise a new value
 * (with its display name + case-folded `nameKey`) is created.
 */
internal class FakeAttributeRepository : AttributeRepository {
    data class AddCall(val type: AttributeType, val name: String, val parentId: String?)

    val addCalls = mutableListOf<AddCall>()
    val attributes = MutableStateFlow<List<AttributeValue>>(emptyList())
    private var counter = 0

    override fun observeAttributes(): Flow<List<AttributeValue>> = attributes

    override suspend fun addAttribute(type: AttributeType, name: String, parentId: String?): String {
        addCalls += AddCall(type, name, parentId)
        val key = AttributeName.matchKey(name)
        attributes.value.firstOrNull { it.type == type && it.parentId == parentId && it.nameKey == key }
            ?.let { return it.attributeId }
        val id = "attr-${++counter}"
        attributes.value = attributes.value +
            AttributeValue(attributeId = id, type = type, name = name, nameKey = key, parentId = parentId)
        return id
    }
}
