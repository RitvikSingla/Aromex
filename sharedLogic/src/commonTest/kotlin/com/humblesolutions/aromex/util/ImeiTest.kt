package com.humblesolutions.aromex.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket #101: validation is Firestore document-id safety, not a 14–16-digit phone rule. Any
 * well-formed identifier is accepted (Aromex carries more than phones); values unsafe as a doc id
 * are rejected.
 */
class ImeiTest {

    @Test
    fun accepts_14_15_16_digit_imeis() {
        assertTrue(Imei.isValid("35693803564380"))    // 14
        assertTrue(Imei.isValid("356938035643809"))   // 15
        assertTrue(Imei.isValid("3569380356438091"))  // 16
    }

    @Test
    fun accepts_long_alphanumeric_serial() {
        assertTrue(Imei.isValid("ABC1234567890XYZ7890")) // 20 chars, real-world serial
    }

    @Test
    fun accepts_up_to_64_chars_and_rejects_over() {
        assertTrue(Imei.isValid("a".repeat(64)))
        assertFalse(Imei.isValid("a".repeat(65)))
    }

    @Test
    fun rejects_blank_after_trim() {
        assertFalse(Imei.isValid(""))
        assertFalse(Imei.isValid("   "))
    }

    @Test
    fun rejects_slash_bearing_value() {
        assertFalse(Imei.isValid("123/456"))
        assertFalse(Imei.isValid("a/b"))
    }

    @Test
    fun rejects_dot_and_dotdot() {
        assertFalse(Imei.isValid("."))
        assertFalse(Imei.isValid(".."))
    }

    @Test
    fun rejects_reserved_double_underscore() {
        assertFalse(Imei.isValid("__foo__"))
        assertFalse(Imei.isValid("____"))
    }

    @Test
    fun accepts_single_dots_and_underscores_inside() {
        // A lone dot / underscore that isn't the reserved pattern is fine.
        assertTrue(Imei.isValid("v1.2.3"))
        assertTrue(Imei.isValid("SN_00123"))
    }

    @Test
    fun trims_before_validating() {
        assertTrue(Imei.isValid("  356938035643809  "))
    }
}
