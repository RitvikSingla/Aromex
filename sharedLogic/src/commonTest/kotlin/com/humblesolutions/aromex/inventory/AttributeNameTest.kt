package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.util.AttributeName
import kotlin.test.Test
import kotlin.test.assertEquals

class AttributeNameTest {

    @Test
    fun normalize_trimsAndCollapsesButKeepsCase() {
        assertEquals("iPhone 15", AttributeName.normalize("  iPhone   15 "))
        assertEquals("Apple", AttributeName.normalize("Apple"))
    }

    @Test
    fun matchKey_isCaseFoldedAndWhitespaceNormalized() {
        val key = AttributeName.matchKey("Apple")
        assertEquals(key, AttributeName.matchKey("apple"))
        assertEquals(key, AttributeName.matchKey("  APPLE "))
        // spaces stripped so "iPhone 15" and "iPhone15" share one key
        assertEquals("iphone15", AttributeName.matchKey(" iPhone  15 "))
        assertEquals(AttributeName.matchKey("128GB"), AttributeName.matchKey("128 GB"))
    }

    @Test
    fun differentNames_haveDifferentKeys() {
        assertEquals(false, AttributeName.matchKey("Apple") == AttributeName.matchKey("Samsung"))
    }
}
