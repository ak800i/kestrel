package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.commands.TextFieldTests.awaitBar
import dev.kestrel.app.commands.TextFieldTests.awaitFocus
import dev.kestrel.app.commands.TextFieldTests.awaitKeyboard
import dev.kestrel.app.commands.TextFieldTests.awaitLine
import dev.kestrel.app.commands.TextFieldTests.WEB_MILLIS
import dev.kestrel.app.commands.TextFieldTests.field
import dev.kestrel.app.commands.TextFieldTests.keepClipboard
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.TextEditor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dictation and the text commands (design spec, section 9.7, and Appendix A), through the same path as speech: each
 * text goes to Kestrel's Orchestrator as a transcript without the name. Like every command test, they run only through
 * scripts\command-test.ps1.
 */
@RunWith(AndroidJUnit4::class)
class TextCommandsTest {
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun startListening() = CommandTests.start()

    @After
    fun stopListening() = CommandTests.stop()

    /** Says [text] and checks that its command acted. */
    private fun done(text: String) = assertEquals(text, Outcome.DONE, CommandTests.say(text).outcome)

    private fun editNotes() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Notes")
        screen.awaitFocus("text_notes")
    }

    /** Whether the web page's field with the HTML ID [id] is in the snapshot: the page loads after its window shows. */
    private fun webFieldShown(id: String): Boolean = runBlocking { screen.session.snapshots.current() }.snapshot.elements.any {
        it.editable && it.packageName == ScreenServiceRule.FIXTURES && it.viewId?.substringAfter(":id/") == id
    }

    @Test
    fun dictationTypesWhatIsSaidWithSpokenPunctuationAndSpelling() {
        editNotes()

        val report = CommandTests.say("hello comma how are you question mark")

        assertEquals(Outcome.DONE, report.outcome)
        assertEquals("Bare", report.kind)
        screen.awaitLine("Notes: \"Hello, how are you?\" 19-19")
        done("capital p one two three")
        screen.awaitLine("Notes: \"Hello, how are you? P123\" 24-24")

        // A phone number field takes only a phone number; other speech goes back to the other parts, and editing works.
        done("start editing Phone")
        screen.awaitFocus("text_phone")
        // The field formats the number as it goes in, as a dialpad does.
        done("plus four four seven nine")
        screen.awaitLine("Phone: \"+44 79\"")
        assertEquals(Outcome.IGNORED, CommandTests.say("hello there").outcome)
        done("undo")
        screen.awaitLine("Phone: \"\"")
    }

    @Test
    fun startEditingTakesTheFirstFieldAndStopEditingEndsDictation() {
        screen.open("EditActivity", "Edit fixture")

        done("start editing")
        screen.awaitFocus("text_search")
        done("falcon")
        screen.awaitLine("Search: \"Falcon\" 6-6")
        // "Stop" may have silenced Kestrel, so it is never typed; the screen has no button with that name.
        assertEquals(Outcome.IGNORED, CommandTests.say("stop").outcome)
        done("stop editing")
        // Dictation is off, so bare speech is ignored; "type" still types, also a word that is a command.
        assertEquals(Outcome.IGNORED, CommandTests.say("more words").outcome)
        done("type delete")
        screen.awaitLine("Search: \"Falcon delete\" 13-13")

        // "Start editing" with a number taps only a text field, never a button such as the phone app's Call.
        val box = runBlocking { screen.awaitElement { it.checkable } }
        done("show numbers")
        check(VoiceTests.waitUntil(5_000) { screen.session.numberBadges.any { it.key == box.key } }) { "The check box has no number" }
        val number = screen.session.numberBadges.first { it.key == box.key }.number
        assertEquals(Outcome.FAILED, CommandTests.say("start editing $number").outcome)
        screen.awaitBar(TextCommandTexts.NOT_A_TEXT_FIELD)
        done("hide numbers")
        assertFalse(runBlocking { screen.awaitElement { it.checkable } }.checked)
    }

    @Test
    fun aNewLineBreaksAFieldWithMoreLinesOnly() {
        editNotes()

        done("first line")
        done("new line")
        done("second line")
        screen.awaitLine("Notes: \"First line\\nsecond line\" 22-22")
        done("start editing Search")
        screen.awaitFocus("text_search")
        assertEquals(Outcome.FAILED, CommandTests.say("new line").outcome)
        screen.awaitBar(TextEditor.ONE_LINE.reason)
    }

    @Test
    fun undoAndRedoUseKestrelsOwnHistory() {
        editNotes()
        val notes = screen.field("text_notes")

        done("apples")
        done("pears")
        screen.awaitLine("Notes: \"Apples pears\" 12-12")
        done("undo")
        screen.awaitLine("Notes: \"Apples\" 6-6")
        done("undo that")
        screen.awaitLine("Notes: \"\" 0-0")
        done("redo")
        screen.awaitLine("Notes: \"Apples\" 6-6")
        // The text changes without Kestrel, so its history no longer fits the field.
        runBlocking { screen.session.actions.setText(notes.key, "changed") }
        screen.awaitLine("Notes: \"changed\" 7-7")
        assertEquals(Outcome.FAILED, CommandTests.say("undo").outcome)
        CommandTests.awaitSaid(TextCommandTexts.textChanged(redo = false))
    }

    @Test
    fun aPasswordFieldHidesTheTranscriptAndSpellsWhatIsDictated() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Password")
        screen.awaitFocus("text_password")
        check(VoiceTests.waitUntil(5_000) { CommandTests.graph.commands.privateInput() }) { "Kestrel did not see the password field" }

        CommandTests.send("capital a bee three")

        screen.awaitBar(ListeningTexts.PRIVATE_INPUT)
        assertEquals(Outcome.DONE, CommandTests.next().outcome)
        screen.awaitLine("Password: 3 characters")
    }

    @Test
    fun dictationWorksInComposeAndOnTheWeb() {
        screen.open("ComposeEditActivity", "Compose edit fixture")
        done("start editing Compose notes")
        screen.awaitFocus("compose_notes")
        done("see you at five period")
        screen.awaitLine("Compose notes: \"See you at five.\" 16-16")

        screen.open("WebEditActivity", "Web edit fixture")
        check(VoiceTests.waitUntil(WEB_MILLIS) { webFieldShown("web_notes") }) { "The web page's field did not appear" }
        done("start editing Web notes")
        screen.awaitFocus("web_notes")
        done("the meeting is on Monday exclamation mark")
        screen.awaitLine("Web notes: \"The meeting is on Monday!\" 25-25")
    }

    @Test
    fun aWebPasswordThatShowPasswordShowsIsStillSpelled() {
        screen.open("WebEditActivity", "Web edit fixture")
        check(VoiceTests.waitUntil(WEB_MILLIS) { webFieldShown("web_password") }) { "The web page's field did not appear" }
        done("start editing Web password")
        screen.awaitFocus("web_password")
        done("a b")
        screen.awaitLine("Web password: 2 characters")

        done("show password")
        screen.awaitLine("Web password shown")
        done("start editing Web password")
        screen.awaitFocus("web_password")
        // The page made it a plain text field, but Kestrel remembers it: "three" is spelled "3", not typed as a word.
        done("three")
        screen.awaitLine("Web password: 3 characters")
    }

    @Test
    fun editingCommandsInAnEditText() {
        editNotes()

        done("type see you tomorrow at noon")
        screen.awaitLine("Notes: \"See you tomorrow at noon\" 24-24")
        done("go to the beginning")
        screen.awaitLine("Notes: \"See you tomorrow at noon\" 0-0")
        done("move after tomorrow")
        screen.awaitLine("Notes: \"See you tomorrow at noon\" 16-16")
        done("insert sharp after noon")
        screen.awaitLine("Notes: \"See you tomorrow at noon sharp\" 30-30")
        done("select tomorrow")
        screen.awaitLine("Notes: \"See you tomorrow at noon sharp\" 8-16")
        done("uppercase tomorrow")
        screen.awaitLine("Notes: \"See you TOMORROW at noon sharp\" 16-16")
        done("replace noon with one")
        screen.awaitLine("Notes: \"See you TOMORROW at one sharp\" 23-23")
        done("delete sharp")
        screen.awaitLine("Notes: \"See you TOMORROW at one\" 23-23")
        done("delete two words")
        screen.awaitLine("Notes: \"See you TOMORROW\" 16-16")
        done("left three characters")
        screen.awaitLine("Notes: \"See you TOMORROW\" 13-13")
        done("select all")
        screen.awaitLine("Notes: \"See you TOMORROW\" 0-16")
        done("delete selected text")
        screen.awaitLine("Notes: \"\" 0-0")
    }

    @Test
    fun editingCommandsInComposeAndOnTheWeb() {
        screen.open("ComposeEditActivity", "Compose edit fixture")
        done("start editing Compose notes")
        screen.awaitFocus("compose_notes")
        done("type hello there world")
        done("delete there")
        screen.awaitLine("Compose notes: \"Hello world\" 5-5")
        done("capitalize world")
        screen.awaitLine("Compose notes: \"Hello World\" 11-11")
        done("select hello")
        screen.awaitLine("Compose notes: \"Hello World\" 0-5")

        screen.open("WebEditActivity", "Web edit fixture")
        check(VoiceTests.waitUntil(WEB_MILLIS) { webFieldShown("web_notes") }) { "The web page's field did not appear" }
        done("start editing Web notes")
        screen.awaitFocus("web_notes")
        done("type red green blue")
        done("replace green with yellow")
        screen.awaitLine("Web notes: \"Red yellow blue\" 10-10")
        done("go to the end")
        done("delete the last word")
        screen.awaitLine("Web notes: \"Red yellow\" 10-10")
        done("select red")
        screen.awaitLine("Web notes: \"Red yellow\" 0-3")
    }

    @Test
    fun theOneWordCommandsEditAFieldAndTapAButtonWithoutOne() {
        editNotes()
        done("type hello world")
        val delete = CommandTests.say("delete")
        assertEquals(Outcome.DONE, delete.outcome)
        assertEquals("Delete", delete.kind)
        screen.awaitLine("Notes: \"Hello\" 5-5")

        // Without a text field, "delete" taps the dialog's only "Delete" button, and "again" taps it once more.
        screen.open("CommandsActivity", "Commands fixture")
        done("tap Open dialog")
        screen.awaitLine("Draft")
        done("Delete")
        screen.awaitLine("Deleted 1 time")
        done("again")
        screen.awaitLine("Deleted 2 times")
    }

    @Test
    fun aPasswordFieldTakesOnlyDeleteAndDeleteAll() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Password")
        screen.awaitFocus("text_password")
        done("a b c d")
        screen.awaitLine("Password: 4 characters")

        done("delete")
        screen.awaitLine("Password: 3 characters")
        assertEquals(Outcome.FAILED, CommandTests.say("delete two words").outcome)
        CommandTests.awaitSaid(TextCommandTexts.PASSWORD_FIELD)
        done("delete all")
        screen.awaitLine("Password: 0 characters")
    }

    @Test
    fun whatADictationTypedBeforeStopCancelledItCanBeUndone() {
        editNotes()
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet", "kilo", "lima")

        // Each new line goes in on its own, so "stop" cancels the dictation between two of its parts.
        CommandTests.send(words.joinToString(" new line "))
        runBlocking {
            screen.awaitElement { it.packageName == ScreenServiceRule.FIXTURES && it.label?.startsWith("Notes: \"Alpha") == true }
        }
        assertEquals(Outcome.CANCELLED, CommandTests.say("stop").outcome)

        done("undo")
        screen.awaitLine("Notes: \"\" 0-0")
    }

    @Test
    fun copyCutAndPasteBetweenTwoFields() = screen.keepClipboard("Other text") {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Search")
        screen.awaitFocus("text_search")
        done("type copy me")
        assertEquals(Outcome.FAILED, CommandTests.say("copy").outcome)
        CommandTests.awaitSaid(TextEditor.SELECT_FIRST.reason)

        done("select copy")
        done("copy")
        done("start editing Paste here")
        screen.awaitFocus("text_paste")
        done("paste")
        screen.awaitLine("Paste: \"Copy\"")
        done("select all")
        done("cut")
        screen.awaitLine("Paste: \"\"")
    }

    @Test
    fun showAndHideTheKeyboard() {
        editNotes()
        screen.awaitKeyboard(shown = true)

        done("hide keyboard")
        screen.awaitKeyboard(shown = false)
        assertEquals(Outcome.FAILED, CommandTests.say("hide the keyboard").outcome)
        screen.awaitBar(TextCommandTexts.KEYBOARD_NOT_SHOWING)
        done("show keyboard")
        screen.awaitKeyboard(shown = true)
    }

    @Test
    fun showPasswordTicksTheCheckBoxNextToTheField() {
        screen.open("EditActivity", "Edit fixture")

        // "Show the password" is no bare label of the check box, so only the text part can take it.
        done("show the password")
        screen.awaitLine("Password shown")
        done("uncheck Show password")
        screen.awaitLine("Password hidden")
        done("show password")
        screen.awaitLine("Password shown")
        // The shown password is still a password field by its input type: dictation spells it.
        done("start editing Password")
        screen.awaitFocus("text_password")
        done("three")
        screen.awaitLine("Password: 1 characters")
    }

    @Test
    fun searchForTypesTheQueryAndSubmitsItInEveryKindOfField() {
        screen.open("EditActivity", "Edit fixture")
        done("search for coffee near me")
        screen.awaitLine("Action 3: \"coffee near me\"")

        screen.open("ComposeEditActivity", "Compose edit fixture")
        done("search for weather tomorrow")
        screen.awaitLine("Searched: \"weather tomorrow\"")

        screen.open("WebEditActivity", "Web edit fixture")
        check(VoiceTests.waitUntil(WEB_MILLIS) { webFieldShown("web_search") }) { "The web page's field did not appear" }
        done("search for coffee near me")
        screen.awaitLine("Submitted: \"coffee near me\"")
    }

    @Test
    fun searchForNeverTypesALineBreak() {
        screen.open("EditActivity", "Edit fixture")

        // A spoken "new line" would go into the one-line search field and be submitted with the query.
        assertEquals(Outcome.FAILED, CommandTests.say("search for coffee new line tea").outcome)
        CommandTests.awaitSaid(TextCommandTexts.LINE_BREAK)
        screen.awaitLine("Search: \"\" 0-0")
    }

    @Test
    fun aSearchButtonsFieldTakesTheQueryAndTheDraftThatHadTheFocusKeepsItsText() {
        screen.open("SearchActivity", "Search button fixture")
        done("start editing Subject")
        screen.awaitFocus("text_subject")
        done("type quarterly report")
        screen.awaitLine("Subject: \"Quarterly report\"")

        // The search field gets the focus a moment after the tap, while the subject still has it.
        done("search for coffee")
        screen.awaitLine("Searched: \"coffee\"")
        screen.awaitLine("Subject: \"Quarterly report\"")
    }

    @Test
    fun searchForReplacesTheWholeAddressThatTheBarShowsOnceItHasTheFocus() {
        screen.open("SearchActivity", "Address fixture", "--ez address true")
        screen.awaitLine("Address: \"example.com/foo\"")

        done("search for coffee")
        screen.awaitLine("Went: \"coffee\"")
        done("undo")
        screen.awaitLine("Address: \"https://example.com/foo\"")
    }

    @Test
    fun aPasswordThatAlreadyShowsHidesTheTranscriptBeforeKestrelSawItHidden() {
        screen.open("EditActivity", "Edit fixture")
        val box = runBlocking { screen.awaitElement { it.checkable && it.label == "Show password" } }
        // The app shows the password before Kestrel ever sees the field, so its node is no password field any more.
        assertTrue(runBlocking { screen.session.actions.tap(box.key) } is ActionResult.Done)
        screen.awaitLine("Password shown")
        done("start editing Password")
        screen.awaitFocus("text_password")

        check(VoiceTests.waitUntil(5_000) { CommandTests.graph.commands.privateInput() }) { "Kestrel did not see the password field" }
        CommandTests.send("a b")
        screen.awaitBar(ListeningTexts.PRIVATE_INPUT)
        assertEquals(Outcome.DONE, CommandTests.next().outcome)
        screen.awaitLine("Password: 2 characters")
    }

    @Test
    fun withoutASearchFieldKestrelSaysSo() {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.FAILED, CommandTests.say("search for coffee").outcome)
        CommandTests.awaitSaid(TextCommandTexts.NO_SEARCH_FIELD)
    }

    // Tasks 6 and 7 add their tests above this line.
}
