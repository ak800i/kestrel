package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenNumbersTest {
    private fun number(text: String, lone: Boolean = false) = SpokenNumbers.number(text.split(" "), lone)

    @Test
    fun readsDigitsAndNumber() {
        assertEquals(21, number("21"))
        assertEquals(7, number("number 7"))
        assertEquals(0, number("0"))
    }

    @Test
    fun readsWordsUpTo999() {
        assertEquals(5, number("five"))
        assertEquals(13, number("thirteen"))
        assertEquals(40, number("forty"))
        assertEquals(21, number("twenty one"))
        assertEquals(100, number("one hundred"))
        assertEquals(105, number("one hundred and five"))
        assertEquals(999, number("nine hundred ninety nine"))
        assertEquals(12, number("number twelve"))
    }

    @Test
    fun anythingElseIsNoNumber() {
        assertNull(number("send"))
        assertNull(number("twenty send"))
        assertNull(number("one hundred and"))
        assertNull(number("five five"))
        assertNull(number("number"))
        assertNull(SpokenNumbers.number(emptyList()))
    }

    @Test
    fun loneHomophonesCountOnlyWhenAllowed() {
        assertEquals(2, number("to", lone = true))
        assertEquals(2, number("too", lone = true))
        assertEquals(4, number("for", lone = true))
        assertEquals(1, number("won", lone = true))
        assertEquals(8, number("ate", lone = true))
        assertEquals(2, number("number to", lone = true))
        assertEquals(2, number("number to"))
        assertNull(number("to"))
        assertNull(number("to for", lone = true))
    }

    @Test
    fun readsTheAnswerToWhichOne() {
        assertEquals(2, SpokenNumbers.answer("2"))
        assertEquals(2, SpokenNumbers.answer("Number 2."))
        assertEquals(2, SpokenNumbers.answer("Tap two"))
        assertEquals(3, SpokenNumbers.answer("click three"))
        assertEquals(2, SpokenNumbers.answer("to"))
        assertEquals(4, SpokenNumbers.answer("For."))
        assertEquals(2, SpokenNumbers.answer("Two, please."))
        assertEquals(3, SpokenNumbers.answer("Okay, number three now"))
        assertNull(SpokenNumbers.answer("scroll down"))
        assertNull(SpokenNumbers.answer("tap send"))
    }
}
