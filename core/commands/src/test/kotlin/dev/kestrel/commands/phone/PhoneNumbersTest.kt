package dev.kestrel.commands.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun spokenNumbersBecomeDigitsWithPlusStarAndHash() {
        assertEquals("0641234567", PhoneNumbers.digits("064 123 4567"))
        assertEquals("0641234567", PhoneNumbers.digits("064-123-4567."))
        assertEquals("0641234567", PhoneNumbers.digits("zero six four one two three four five six seven"))
        assertEquals("+381641234567", PhoneNumbers.digits("plus 381 64 123 45 67"))
        assertEquals("+381641234567", PhoneNumbers.digits("+381 (64) 123-4567"))
        assertEquals("*123#", PhoneNumbers.digits("star one two three hash"))
        assertEquals("#31#", PhoneNumbers.digits("pound 3 1 pound"))
        assertEquals("112", PhoneNumbers.digits("One one two."))
    }

    @Test
    fun anythingElseIsNoNumber() {
        assertNull(PhoneNumbers.digits("Ana"))
        assertNull(PhoneNumbers.digits("064 Ana"))
        assertNull(PhoneNumbers.digits("plus"))
        assertNull(PhoneNumbers.digits("064 plus 1"))
        assertNull(PhoneNumbers.digits(""))
        // A digit of another script is refused, never skipped, so that no other number comes out of it.
        assertNull(PhoneNumbers.digits("064 \u0661\u0662\u0663 4567"))
        assertNull(PhoneNumbers.digits("\u0661\u0661\u0662"))
    }

    @Test
    fun theScreensNumbersAreFoundAndDatesAreNot() {
        val text = "Office: +381 11 234 5678, mobile 064/123-45-67? No: 064 123 4567. Meeting on 17.11.2026 or 2026-11-17, room 12."
        val found = PhoneNumbers.find(text).map { text.substring(it) }

        assertEquals(listOf("+381 11 234 5678", "064/123-45-67", "064 123 4567"), found)
        assertEquals(listOf("(011) 234-5678"), PhoneNumbers.find("Call (011) 234-5678 today").map { "Call (011) 234-5678 today".substring(it) })
        for (date in listOf("17/11/26 at 10.30, code 12345", "Sent 17.11.2026 10:30", "17. 11. 2026.", "2026-11-17 10:30")) {
            assertTrue(date, PhoneNumbers.find(date).isEmpty())
        }
    }

    @Test
    fun aShortGroupAfterAWordIsNoPartOfTheNumberAndAmountsAndCoordinatesAreNoNumbers() {
        fun found(text: String) = PhoneNumbers.find(text).map { text.substring(it) }

        assertEquals(listOf("064 123 4567"), found("Room 12 064 123 4567"))
        assertEquals(listOf("064 123 4567"), found("Floor 3 064 123 4567"))
        // A group that starts with "0" begins the number, as in French and Dutch numbers.
        assertEquals(listOf("06 12 34 56 78"), found("Tél 06 12 34 56 78"))
        assertEquals(listOf("06 12345678"), found("Mobiel 06 12345678"))
        for (text in listOf("Total 1.250.000,00 RSD", "Total 250000,00 RSD", "Paid 1,250,000 today", "At 44.812345, 20.461234")) {
            assertEquals(text, emptyList<String>(), found(text))
        }
        // Phone numbers with dots still count.
        assertEquals(listOf("064.123.4567", "011.234.5678"), found("Call 064.123.4567 or 011.234.5678"))
    }

    @Test
    fun kestrelPressesCallOnlyForLongNumbersWithoutStarOrHash() {
        assertTrue(PhoneNumbers.mayAutoDial("0641234567"))
        assertTrue(PhoneNumbers.mayAutoDial("+381641234567"))
        assertFalse(PhoneNumbers.mayAutoDial("112"))
        assertFalse(PhoneNumbers.mayAutoDial("1234"))
        assertFalse(PhoneNumbers.mayAutoDial("*#06#"))
        assertFalse(PhoneNumbers.mayAutoDial("*1230641234567"))
    }

    @Test
    fun storedNumbersKeepTheirPausesWaitsAndExtensionsForThePhoneApp() {
        assertEquals("0641234567,123", PhoneNumbers.dialable("064 123 4567,123"))
        assertEquals("+381641234567;99", PhoneNumbers.dialable("+381 (64) 123-4567;99"))
        assertEquals("0641234567,12", PhoneNumbers.dialable("064 123 4567 ext. 12"))
        assertEquals("0641234567,12", PhoneNumbers.dialable("064 123 4567 x12"))
        assertEquals("0641234567;12", PhoneNumbers.dialable("0641234567w12"))
        assertEquals("0112345678", PhoneNumbers.dialable("011/234-5678"))
        assertNull(PhoneNumbers.dialable("1-800-FLOWERS"))
        assertTrue(PhoneNumbers.mayAutoDial("0641234567,123"))
        assertFalse(PhoneNumbers.mayAutoDial("112,5"))
        assertFalse(PhoneNumbers.mayAutoDial("1-800-FLOWERS"))
    }
}
