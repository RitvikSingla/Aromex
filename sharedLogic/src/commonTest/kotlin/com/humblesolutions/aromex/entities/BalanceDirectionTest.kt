package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.model.BalanceDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceDirectionTest {

    @Test
    fun fromBalance_mapsSignToDirection() {
        assertEquals(BalanceDirection.RECEIVABLE, BalanceDirection.fromBalance("500.00"))
        assertEquals(BalanceDirection.RECEIVABLE, BalanceDirection.fromBalance("+5"))
        assertEquals(BalanceDirection.CREDIT, BalanceDirection.fromBalance("-500.00"))
        assertEquals(BalanceDirection.SETTLED, BalanceDirection.fromBalance("0"))
        assertEquals(BalanceDirection.SETTLED, BalanceDirection.fromBalance("0.00"))
        assertEquals(BalanceDirection.SETTLED, BalanceDirection.fromBalance("-0.00"))
    }
}
