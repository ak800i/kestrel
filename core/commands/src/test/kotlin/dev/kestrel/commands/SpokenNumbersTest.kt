package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertNull(number("one hundred zero"))
        assertNull(number("one hundred and zero"))
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
        // Every answer that the grammar reads as a tap on a number.
        assertEquals(2, SpokenNumbers.answer("tap on two"))
        assertEquals(2, SpokenNumbers.answer("Can you tap 2?"))
        assertEquals(2, SpokenNumbers.answer("click on number two"))
        assertEquals(2, SpokenNumbers.answer("Could you press on to, please"))
        assertNull(SpokenNumbers.answer("tap on"))
        assertNull(SpokenNumbers.answer("can you"))
        assertNull(SpokenNumbers.answer("scroll down"))
        assertNull(SpokenNumbers.answer("tap send"))
    }

    @Test
    fun aCommandThatNamesANumberWithAnyVerbIsNoPlainAnswer() {
        for (said in listOf(
            "long press 2", "check 2", "uncheck 2", "switch off 2", "dismiss 2", "expand 2", "increment 2", "drag 2 to 5",
            "drag Photo to 5", "scroll down on 2", "swipe left on 2", "pinch out 2", "what is 2", "show commands for 2",
        )) {
            assertTrue(said, SpokenNumbers.namesNumber(CommandParser.parse(said)))
        }
        for (said in listOf("scroll down", "long press Send", "check Remember me", "drag Photo to Album", "send", "thank you")) {
            assertFalse(said, SpokenNumbers.namesNumber(CommandParser.parse(said)))
        }
        assertFalse(SpokenNumbers.namesNumber(null))
    }
}
