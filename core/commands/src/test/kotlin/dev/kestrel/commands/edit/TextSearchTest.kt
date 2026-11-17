package dev.kestrel.commands.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSearchTest {
    @Test
    fun aPhraseIsFoundAfterNormalisingAndMappedBackToTheFieldsOffsets() {
        val text = "Meet Đorđe at the Café, then lunch."

        assertEquals(5..9, TextSearch.find(text, "djordje", 0))
        assertEquals(18..21, TextSearch.find(text, "cafe", 0))
        assertEquals(14..21, TextSearch.find(text, "the café", 0))
        assertEquals(8..12, TextSearch.find("Здраво, Ђорђе!", "Djordje", 0))
    }

    @Test
    fun theHitNearestToTheCursorWins() {
        val text = "cat dog cat dog cat"

        // The one that ends nearest before the cursor.
        assertEquals(8..10, TextSearch.find(text, "cat", 11))
        // The one that holds the cursor.
        assertEquals(8..10, TextSearch.find(text, "cat", 9))
        // Else the nearest after it.
        assertEquals(0..2, TextSearch.find(text, "cat", 0))
        assertEquals(4..6, TextSearch.find(text, "dog", 3))
    }

    @Test
    fun aSimilarRunOfWordsCountsAndALeadingArticleMayBeMissing() {
        assertEquals(4..10, TextSearch.find("the meeting is at noon", "meting", 0))
        assertEquals(0..4, TextSearch.find("Lunch with Ana tomorrow", "the lunch", 23))
        assertNull(TextSearch.find("the meeting is at noon", "dinner", 0))
        assertNull(TextSearch.find("anything", "", 0))
    }
}
