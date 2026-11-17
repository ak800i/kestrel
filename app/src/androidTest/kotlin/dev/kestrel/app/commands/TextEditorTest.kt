package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.commands.TextFieldTests.awaitLine
import dev.kestrel.app.commands.TextFieldTests.field
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ClipboardKind
import dev.kestrel.screen.act.TextEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kestrel's text editor in every kind of field (design spec, section 9.7, and prototype P4): `EditText`, the three
 * Compose text fields and the web page's input, textarea and contenteditable. It runs only through
 * scripts\command-test.ps1, like every test of this package; the first rule skips it before the second turns anything on.
 */
@RunWith(AndroidJUnit4::class)
class TextEditorTest {
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val editor: TextEditor get() = screen.session.text

    private val throughInputMethod = ActionResult.Done(ActionMethod.INPUT_METHOD)

    @Test
    fun readGivesAnEmptyFieldWithoutItsHintAndTheFieldsKind() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")

        val search = checkNotNull(editor.read(screen.field("text_search").key))
        assertEquals("", search.text)
        assertEquals(0, search.selectionStart)
        assertFalse(search.password)
        assertFalse(search.multiLine)
        assertTrue(checkNotNull(editor.read(screen.field("text_notes").key)).multiLine)
        assertTrue(checkNotNull(editor.read(screen.field("text_password").key)).password)
    }

    @Test
    fun anEditTextTakesEditsAndSelectionsThroughTheInputMethod() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val search = screen.field("text_search").key

        assertEquals(throughInputMethod, editor.replace(search, 0, 0, "hello world", 11, 11))
        screen.awaitLine("Search: \"hello world\" 11-11")
        // Only the changed range goes in, and the selection afterwards is Kestrel's.
        assertEquals(throughInputMethod, editor.replace(search, 6, 11, "there", 6, 11))
        screen.awaitLine("Search: \"hello there\" 6-11")
        assertTrue(editor.select(search, 0, 5) is ActionResult.Done)
        screen.awaitLine("Search: \"hello there\" 0-5")
        assertEquals("hello there", checkNotNull(editor.read(search)).text)
    }

    @Test
    fun aNewLineNeedsAFieldWithMoreLinesAndSubmitUsesTheFieldsAction() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val search = screen.field("text_search").key
        val notes = screen.field("text_notes").key

        assertEquals(TextEditor.ONE_LINE, editor.newLine(search))
        editor.replace(notes, 0, 0, "first", 5, 5)
        // The notes field has the focus, and its input has no search action.
        assertFalse(editor.searches(notes))
        assertFalse(editor.phone(notes))
        assertTrue(editor.phone(screen.field("text_phone").key))
        assertTrue(editor.newLine(notes) is ActionResult.Done)
        editor.replace(notes, 6, 6, "second", 12, 12)
        screen.awaitLine("Notes: \"first\\nsecond\" 12-12")
        assertEquals(TextEditor.NO_ACTION, editor.submit(notes))

        editor.replace(search, 0, 0, "kestrel", 7, 7)
        assertTrue(editor.searches(search))
        assertTrue(editor.submit(search) is ActionResult.Done)
        // The app's listener gets the search action (3), not the action 0 that the IME-enter action sends (P4).
        screen.awaitLine("Action 3: \"kestrel\"")
    }

    @Test
    fun cutCopyAndPasteActOnTheFocusedField() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val search = screen.field("text_search").key
        val paste = screen.field("text_paste").key
        editor.replace(search, 0, 0, "copy me", 7, 7)

        assertEquals(TextEditor.SELECT_FIRST, editor.clipboard(search, ClipboardKind.COPY))
        editor.select(search, 0, 4)
        assertTrue(editor.clipboard(search, ClipboardKind.COPY) is ActionResult.Done)
        assertTrue(editor.clipboard(paste, ClipboardKind.PASTE) is ActionResult.Done)
        screen.awaitLine("Paste: \"copy\"")
        // The paste field has the focus now, and cut needs no more.
        editor.select(paste, 0, 2)
        assertTrue(editor.clipboard(paste, ClipboardKind.CUT) is ActionResult.Done)
        screen.awaitLine("Paste: \"py\"")
    }

    @Test
    fun aPasswordFieldTypesAndDeletesAtTheCursor() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val password = screen.field("text_password").key

        assertEquals(throughInputMethod, editor.typeAtCursor(password, "abc1"))
        screen.awaitLine("Password: 4 characters")
        assertEquals(throughInputMethod, editor.deleteBeforeCursor(password, 1))
        screen.awaitLine("Password: 3 characters")
        val dots = checkNotNull(editor.read(password)).text.length
        assertTrue(editor.replace(password, 0, dots, "", 0, 0) is ActionResult.Done)
        screen.awaitLine("Password: 0 characters")
    }

    @Test
    fun composeFieldsTakeEditsAndTheInputTellsWhichHasMoreLines() = runBlocking<Unit> {
        screen.open("ComposeEditActivity", "Compose edit fixture")
        val search = screen.field("compose_search").key
        val notes = screen.field("compose_notes").key
        val state = screen.field("compose_state").key

        assertEquals(throughInputMethod, editor.replace(notes, 0, 0, "one two", 7, 7))
        screen.awaitLine("Compose notes: \"one two\" 7-7")
        // Compose's node says one line for every field; the input that the app started knows better.
        assertTrue(checkNotNull(editor.read(notes)).multiLine)
        assertTrue(editor.newLine(notes) is ActionResult.Done)
        screen.awaitLine("Compose notes: \"one two\\n\" 8-8")
        assertTrue(editor.select(notes, 0, 3) is ActionResult.Done)
        screen.awaitLine("Compose notes: \"one two\\n\" 0-3")

        assertEquals(throughInputMethod, editor.replace(state, 0, 0, "state", 5, 5))
        screen.awaitLine("Compose state: \"state\" 5-5")
        assertEquals(throughInputMethod, editor.replace(search, 0, 0, "tea", 3, 3))
        screen.awaitLine("Compose search: \"tea\"")
        assertFalse(checkNotNull(editor.read(search)).multiLine)
        assertEquals(TextEditor.ONE_LINE, editor.newLine(search))
        assertTrue(editor.submit(search) is ActionResult.Done)
        screen.awaitLine("Searched: \"tea\"")
    }

    @Test
    fun webFieldsTakeEditsSelectionsLinesAndSubmit() = runBlocking<Unit> {
        screen.open("WebEditActivity", "Web edit fixture")
        val search = screen.field("web_search").key
        val notes = screen.field("web_notes").key
        val rich = screen.field("web_rich").key

        assertEquals(throughInputMethod, editor.replace(notes, 0, 0, "a", 1, 1))
        assertTrue(editor.newLine(notes) is ActionResult.Done)
        screen.awaitLine("Web notes: \"a\\n\" 2-2")
        assertEquals(throughInputMethod, editor.replace(rich, 0, 0, "rich", 4, 4))
        screen.awaitLine("Web rich text: \"rich\"")

        assertEquals(throughInputMethod, editor.replace(search, 0, 0, "hello", 5, 5))
        screen.awaitLine("Web search: \"hello\" 5-5")
        assertTrue(editor.select(search, 0, 2) is ActionResult.Done)
        screen.awaitLine("Web search: \"hello\" 0-2")
        assertTrue(editor.submit(search) is ActionResult.Done)
        screen.awaitLine("Submitted: \"hello\"")
    }
}
