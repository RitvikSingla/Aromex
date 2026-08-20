package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.util.SkuKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SkuKeyTest {

    @Test
    fun build_joinsFiveIdsInCanonicalOrder() {
        val key = SkuKey.build(skuAttributes())
        assertEquals("b1_m1_cap1_col1_car1", key)
    }

    @Test
    fun build_isOrderIndependent() {
        val inOrder = linkedMapOf(
            AttributeType.BRAND to AttributeRef("b1"),
            AttributeType.MODEL to AttributeRef("m1"),
            AttributeType.CAPACITY to AttributeRef("cap1"),
            AttributeType.COLOR to AttributeRef("col1"),
            AttributeType.CARRIER to AttributeRef("car1"),
        )
        val shuffled = linkedMapOf(
            AttributeType.CARRIER to AttributeRef("car1"),
            AttributeType.COLOR to AttributeRef("col1"),
            AttributeType.BRAND to AttributeRef("b1"),
            AttributeType.CAPACITY to AttributeRef("cap1"),
            AttributeType.MODEL to AttributeRef("m1"),
        )
        assertEquals(SkuKey.build(inOrder), SkuKey.build(shuffled))
    }

    @Test
    fun build_ignoresLocation() {
        val withLocation = skuAttributes() + (AttributeType.LOCATION to AttributeRef("loc1", "Warehouse A"))
        assertEquals(SkuKey.build(skuAttributes()), SkuKey.build(withLocation))
    }

    // ── ticket #101: capacity/colour/carrier optional, brand/model still required ──

    @Test
    fun build_blankCapacityKeepsEmptySegment() {
        // Blank capacity yields a DIFFERENT key from a populated one, and keeps its empty slot.
        val blankCap = SkuKey.build(skuAttributes(capacity = ""))
        assertEquals("b1_m1__col1_car1", blankCap)
        assertNotEquals(SkuKey.build(skuAttributes()), blankCap)
    }

    @Test
    fun build_capacityOnlyAndColourOnlyYieldDifferentKeys() {
        // The collision the empty segment prevents: without it, both would be "b1_m1_X".
        val capacityOnly = SkuKey.build(skuAttributes(capacity = "x", color = "", carrier = ""))
        val colourOnly = SkuKey.build(skuAttributes(capacity = "", color = "x", carrier = ""))
        assertNotEquals(capacityOnly, colourOnly)
        assertEquals("b1_m1_x__", capacityOnly)
        assertEquals("b1_m1__x_", colourOnly)
    }

    @Test
    fun build_allBlankOptionalAttributesIsDeterministic() {
        val a = SkuKey.build(skuAttributes(capacity = "", color = "", carrier = ""))
        val b = SkuKey.build(skuAttributes(capacity = "", color = "", carrier = ""))
        assertEquals("b1_m1___", a)
        assertEquals(a, b)
    }

    @Test
    fun build_missingOptionalAttributeKeepsEmptySegment() {
        val missingCarrier = skuAttributes() - AttributeType.CARRIER
        assertEquals("b1_m1_cap1_col1_", SkuKey.build(missingCarrier))
    }

    @Test
    fun build_rejectsBlankBrand() {
        assertFailsWith<IllegalArgumentException> { SkuKey.build(skuAttributes(brand = "  ")) }
    }

    @Test
    fun build_rejectsBlankModel() {
        assertFailsWith<IllegalArgumentException> { SkuKey.build(skuAttributes(model = "")) }
    }

    @Test
    fun build_rejectsMissingBrand() {
        assertFailsWith<IllegalArgumentException> { SkuKey.build(skuAttributes() - AttributeType.BRAND) }
    }
}
