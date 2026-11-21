package dev.kestrel.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelWordsTest {
    @Test
    fun theMostRestrictiveCategoryWinsInBothScriptsAndBothLanguages() {
        assertEquals(Category.SENDING, LabelWords.categoryOf("Send"))
        assertEquals(Category.SENDING, LabelWords.categoryOf("Пошаљи"))
        assertEquals(Category.SENDING, LabelWords.categoryOf("Pošaljite poruku"))
        assertEquals(Category.SENDING, LabelWords.categoryOf("Record voice message"))
        assertEquals(Category.CALLS, LabelWords.categoryOf("Позови"))
        assertEquals(Category.DELETING, LabelWords.categoryOf("Obrišite razgovor"))
        assertEquals(Category.PAYMENTS, LabelWords.categoryOf("Place order"))
        assertEquals(Category.PAYMENTS, LabelWords.categoryOf("Naruči"))
        assertEquals(Category.PROTECTED, LabelWords.categoryOf("Dozvoli"))
        // "Share and delete" holds both; deleting is the lower row of the table.
        assertEquals(Category.DELETING, LabelWords.categoryOf("Share and delete"))
    }

    @Test
    fun onlyWholeWordsCountAndABareActivateIsNone() {
        assertNull(LabelWords.categoryOf("Sender"))
        assertNull(LabelWords.categoryOf("Callback settings"))
        assertNull(LabelWords.categoryOf("Activate"))
        assertEquals(Category.PROTECTED, LabelWords.categoryOf("Activate this device admin app"))
        assertNull(LabelWords.categoryOf(null))
    }

    @Test
    fun saveAndShareWordsAreFoundWhole() {
        assertTrue(LabelWords.holdsWord("Sačuvaj", LabelWords.SAVE_WORDS))
        assertTrue(LabelWords.holdsWord("O.K.", LabelWords.SAVE_WORDS))
        assertTrue(LabelWords.holdsWord("Prosledi", LabelWords.SHARE_START_WORDS))
        assertFalse(LabelWords.holdsWord("Shared albums", LabelWords.SHARE_START_WORDS))
    }
}
