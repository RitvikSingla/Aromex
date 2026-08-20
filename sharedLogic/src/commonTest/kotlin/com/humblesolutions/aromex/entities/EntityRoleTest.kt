package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.model.EntityRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntityRoleTest {

    @Test
    fun fromWire_roundTripsUppercaseNames() {
        assertEquals(EntityRole.CUSTOMER, EntityRole.fromWire("CUSTOMER"))
        assertEquals(EntityRole.SUPPLIER, EntityRole.fromWire("SUPPLIER"))
        assertEquals(EntityRole.MIDDLEMAN, EntityRole.fromWire("  MIDDLEMAN  "))
    }

    @Test
    fun fromWire_rejectsUnknownOrWrongCase() {
        assertNull(EntityRole.fromWire("customer")) // wire is UPPERCASE == enum name
        assertNull(EntityRole.fromWire("BROKER"))
        assertNull(EntityRole.fromWire(""))
    }

    @Test
    fun wireFormIsEnumName() {
        // Guard: the persisted string is the enum name verbatim on every platform.
        assertEquals("CUSTOMER", EntityRole.CUSTOMER.name)
        assertEquals("SUPPLIER", EntityRole.SUPPLIER.name)
        assertEquals("MIDDLEMAN", EntityRole.MIDDLEMAN.name)
    }
}
