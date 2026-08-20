package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.TrackingMode
import com.humblesolutions.aromex.util.Imei
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryEnumsTest {

    @Test
    fun trackingMode_roundTripsAndRejectsUnknown() {
        TrackingMode.entries.forEach { assertEquals(it, TrackingMode.fromWire(it.name)) }
        assertEquals(TrackingMode.SERIALIZED, TrackingMode.fromWire(" SERIALIZED "))
        assertNull(TrackingMode.fromWire("serialized")) // wire is UPPERCASE
        assertNull(TrackingMode.fromWire("BOGUS"))
    }

    @Test
    fun condition_roundTripsAndRejectsUnknown() {
        Condition.entries.forEach { assertEquals(it, Condition.fromWire(it.name)) }
        assertNull(Condition.fromWire("REFURBISHED"))
    }

    @Test
    fun serialStatus_roundTripsAndRejectsUnknown() {
        SerialStatus.entries.forEach { assertEquals(it, SerialStatus.fromWire(it.name)) }
        assertNull(SerialStatus.fromWire("LOST"))
    }

    @Test
    fun attributeType_wireIsLowercaseAndRoundTrips() {
        AttributeType.entries.forEach {
            assertEquals(it.wire, it.wire.lowercase())
            assertEquals(it, AttributeType.fromWire(it.wire))
        }
        assertEquals(AttributeType.BRAND, AttributeType.fromWire(" brand "))
        assertNull(AttributeType.fromWire("BRAND")) // wire is lowercase
        assertNull(AttributeType.fromWire("size"))
    }

    @Test
    fun attributeType_skuDefiningExcludesLocation() {
        assertFalse(AttributeType.LOCATION in AttributeType.SKU_DEFINING)
        assertEquals(5, AttributeType.SKU_DEFINING.size)
    }

    @Test
    fun imei_validatesDocIdSafety() {
        // Ticket #101: doc-id safety, not a length rule. Full coverage lives in ImeiTest.
        assertTrue(Imei.isValid("356938035643809"))   // 15 digits
        assertTrue(Imei.isValid(" 35693803564380 "))  // trimmed
        assertTrue(Imei.isValid("35693803564380A"))   // alphanumeric serial now allowed
        assertFalse(Imei.isValid("12/345"))           // slash unsafe as a doc id
        assertFalse(Imei.isValid(""))                 // blank
    }
}
