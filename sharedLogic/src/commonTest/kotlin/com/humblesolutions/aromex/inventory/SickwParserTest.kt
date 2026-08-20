package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.UnreadableBlock
import com.humblesolutions.aromex.util.parseSickw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SickwParserTest {

    /** The exact SICKW sample the ticket pins the parser to. */
    private val FIXTURE = """
        Model Description: IPHONE 14,ROW,256GB,PURPLE
        IMEI: 353340195540565
        IMEI2: 353340199954622
        MEID: 35334019554056
        Serial Number: KJPK6N7TLG
        Estimated Purchase Date: 2023-04-28
        Warranty Status: Out Of Warranty
        Demo Unit: No
        Locked Carrier: 10 - Unlock
        Sim-Lock Status: Unlocked
    """.trimIndent()

    @Test
    fun fixture_parsesToApple_iPhone14_256GB_Purple_Unlocked() {
        val result = parseSickw(FIXTURE)

        assertEquals(0, result.unreadable.size, "fixture should be fully readable")
        assertEquals(1, result.phones.size)
        val p = result.phones.single()

        assertEquals("353340195540565", p.imei) // primary IMEI, not IMEI2/MEID
        assertEquals("Apple", p.brand)           // inferred from IPHONE
        assertEquals("iPhone 14", p.model)       // normalized casing
        assertEquals("256GB", p.capacity)
        assertEquals("Purple", p.color)          // PURPLE → Purple
        assertEquals("Unlocked", p.carrier)      // Sim-Lock Status governs
    }

    @Test
    fun multiplePhonesInOnePaste_splitIntoBlocks() {
        val second = """
            Model Description: IPHONE 13 PRO,LL/A,128GB,SIERRA BLUE
            IMEI: 356789104567890
            Sim-Lock Status: Unlocked
        """.trimIndent()

        val result = parseSickw(FIXTURE + "\n\n" + second)

        assertEquals(0, result.unreadable.size)
        assertEquals(2, result.phones.size)
        assertEquals("353340195540565", result.phones[0].imei)

        val p2 = result.phones[1]
        assertEquals("iPhone 13 Pro", p2.model)
        assertEquals("128GB", p2.capacity)
        assertEquals("Sierra Blue", p2.color)
        assertEquals("356789104567890", p2.imei)
        assertEquals("Unlocked", p2.carrier)
    }

    @Test
    fun lockedCarrierPhone_usesCarrierName() {
        val locked = """
            Model Description: IPHONE 12,ZP/A,64GB,BLACK
            IMEI: 358234567890123
            Locked Carrier: 15 - AT&T USA
            Sim-Lock Status: Locked
        """.trimIndent()

        val p = parseSickw(locked).phones.single()
        assertEquals("iPhone 12", p.model)
        assertEquals("Black", p.color)
        assertEquals("AT&T USA", p.carrier) // code prefix "15 - " stripped, name kept
    }

    @Test
    fun nonIphoneBlock_isUnreadable_notDropped() {
        val android = """
            Model Description: GALAXY S23,256GB,PHANTOM BLACK
            IMEI: 359876543210987
        """.trimIndent()

        val result = parseSickw(android)
        assertEquals(0, result.phones.size)
        assertEquals(1, result.unreadable.size)
        assertEquals(UnreadableBlock.Reason.NOT_IPHONE, result.unreadable.single().reason)
        // raw text preserved so the UI can show it
        assertTrue(result.unreadable.single().rawText.contains("GALAXY S23"))
    }

    @Test
    fun blockWithoutImei_isUnreadable() {
        val noImei = """
            Model Description: IPHONE 14,ROW,256GB,PURPLE
            Warranty Status: Out Of Warranty
        """.trimIndent()

        val result = parseSickw(noImei)
        assertEquals(0, result.phones.size)
        assertEquals(UnreadableBlock.Reason.NO_IMEI, result.unreadable.single().reason)
    }

    @Test
    fun readablePhoneAndUnreadableBlock_bothReturned() {
        val mixed = FIXTURE + "\n\n" + """
            Model Description: GALAXY S23,256GB,PHANTOM BLACK
            IMEI: 359876543210987
        """.trimIndent()

        val result = parseSickw(mixed)
        assertEquals(1, result.phones.size)
        assertEquals(1, result.unreadable.size)
    }

    @Test
    fun vocabNormalization_titleCasesModelColorCapacity() {
        val messy = """
            Model Description: iphone 15 pro max,row,1tb,natural titanium
            IMEI: 351111112222223
            Sim-Lock Status: Unlocked
        """.trimIndent()

        val p = parseSickw(messy).phones.single()
        assertEquals("iPhone 15 Pro Max", p.model)
        assertEquals("1TB", p.capacity)              // lowercase unit + no space → 1TB
        assertEquals("Natural Titanium", p.color)
    }

    @Test
    fun capacityWithSpace_isNormalizedNoSpaceUppercase() {
        val spaced = """
            Model Description: IPHONE 14,ROW,256 gb,PURPLE
            IMEI: 353340195540565
            Sim-Lock Status: Unlocked
        """.trimIndent()

        assertEquals("256GB", parseSickw(spaced).phones.single().capacity)
    }

    @Test
    fun emptyText_yieldsEmptyResult() {
        val result = parseSickw("   \n  ")
        assertTrue(result.phones.isEmpty())
        assertTrue(result.unreadable.isEmpty())
    }

    @Test
    fun regionCodes_areIgnored_notMistakenForColor() {
        // ROW / LL are region codes and must never become the colour.
        val p = parseSickw(FIXTURE).phones.single()
        assertEquals("Purple", p.color)
    }
}
