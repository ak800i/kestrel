package dev.kestrel.commands.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private val empty = TextState("", 0, 0)
    private val hello = TextState("hello", 5, 5)
    private val helloWorld = TextState("hello world", 11, 11)

    @Test
    fun undoAndRedoPutBackTheStatesAroundEachEdit() {
        val history = EditHistory()
        history.record("app/field", empty, hello)
        history.record("app/field", hello, helloWorld)

        assertEquals(hello, history.undo("app/field", helloWorld))
        assertEquals(empty, history.undo("app/field", hello))
        assertFalse(history.canUndo("app/field"))
        assertEquals(hello, history.redo("app/field", empty))
        assertEquals(helloWorld, history.redo("app/field", hello))
        assertFalse(history.canRedo("app/field"))
    }

    @Test
    fun aNewEditAfterAnUndoDropsWhatRedoCouldRestore() {
        val history = EditHistory()
        history.record("app/field", empty, hello)
        history.record("app/field", hello, helloWorld)
        history.undo("app/field", helloWorld)

        history.record("app/field", hello, TextState("hello there", 11, 11))

        assertFalse(history.canRedo("app/field"))
        assertEquals(hello, history.undo("app/field", TextState("hello there", 11, 11)))
    }

    @Test
    fun aTextThatChangedSinceKestrelsEditDropsTheFieldsHistory() {
        val history = EditHistory()
        history.record("app/field", empty, hello)

        assertNull(history.undo("app/field", TextState("hello!", 6, 6)))
        assertFalse(history.canUndo("app/field"))
        // Only the text counts; the cursor may have moved.
        history.record("app/field", empty, hello)
        assertEquals(empty, history.undo("app/field", TextState("hello", 0, 0)))
    }

    @Test
    fun aRedoAfterTheTextChangedDropsTheFieldsHistory() {
        val history = EditHistory()
        history.record("app/field", empty, hello)
        history.record("app/field", hello, helloWorld)
        history.undo("app/field", helloWorld)

        assertNull(history.redo("app/field", TextState("hello!", 6, 6)))
        assertFalse(history.canRedo("app/field"))
        assertFalse(history.canUndo("app/field"))
    }

    @Test
    fun itKeepsFiftyEditsOfTwentyFields() {
        val history = EditHistory()
        val states = (0..51).map { TextState("a".repeat(it), it, it) }
        for (index in 0 until 51) history.record("app/field", states[index], states[index + 1])

        var current = states[51]
        repeat(50) { current = checkNotNull(history.undo("app/field", current)) }
        assertEquals(states[1], current)
        assertFalse(history.canUndo("app/field"))

        for (field in 1..21) history.record("app/$field", empty, hello)
        assertFalse(history.canUndo("app/1"))
        assertTrue(history.canUndo("app/21"))
        assertTrue(history.canUndo("app/2"))
    }
}
