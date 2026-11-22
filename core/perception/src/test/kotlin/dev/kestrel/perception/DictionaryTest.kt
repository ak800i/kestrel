package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DictionaryTest {
    @Test
    fun theBlankComesFirstAndTheSpaceLastWithRepeatedLinesKept() {
        val dictionary = Dictionary.parse("a\nb\na\n")

        assertEquals(5, dictionary.classes)
        assertEquals(listOf("", "a", "b", "a", " "), (0 until 5).map(dictionary::label))
    }

    @Test
    fun aRecogniserWhoseClassesDoNotFitItsDictionaryIsRefused() {
        val dictionary = Dictionary.parse("a\nb\n")

        dictionary.check(4)
        val error = assertThrows(IllegalStateException::class.java) { dictionary.check(5) }
        assertEquals("The recogniser has 5 classes, but its dictionary needs 4", error.message)
    }
}
