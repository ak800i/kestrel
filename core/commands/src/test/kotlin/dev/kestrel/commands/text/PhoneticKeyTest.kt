package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneticKeyTest {
    @Test
    fun serbianNamesAndTheirEnglishSpellingsShareAKey() {
        assertEquals("jrj", PhoneticKey.of("Đorđe"))
        assertEquals("jrj", PhoneticKey.of("Ђорђе"))
        assertEquals("jrj", PhoneticKey.of("Georgie"))
        assertEquals("jrj", PhoneticKey.of("Giorgie"))
        assertEquals("tnch", PhoneticKey.of("Tonči"))
        assertEquals("tnch", PhoneticKey.of("Tonchi"))
    }

    @Test
    fun keepsALeadingVowelDropsTheOthersAndCollapsesRepeats() {
        assertEquals("vbr", PhoneticKey.of("Viber"))
        assertEquals("vbr", PhoneticKey.of("veiber"))
        assertEquals("an", PhoneticKey.of("Anna"))
        assertEquals("wf", PhoneticKey.of("Wi-Fi"))
    }

    @Test
    fun voicedAndUnvoicedPairsStayApart() {
        assertNotEquals(PhoneticKey.of("Save"), PhoneticKey.of("Safe"))
        assertNotEquals(PhoneticKey.of("Back"), PhoneticKey.of("Pack"))
    }
}
