package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.commands.TextFieldTests.awaitBar
import dev.kestrel.app.commands.TextFieldTests.awaitFocus
import dev.kestrel.app.commands.TextFieldTests.awaitLine
import dev.kestrel.app.commands.TextFieldTests.field
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.TextEditor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        done("plus four four seven nine")
        screen.awaitLine("Phone: \"+4479\"")
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
        check(VoiceTests.waitUntil(10_000) { webFieldShown("web_notes") }) { "The web page's field did not appear" }
        done("start editing Web notes")
        screen.awaitFocus("web_notes")
        done("the meeting is on Monday exclamation mark")
        screen.awaitLine("Web notes: \"The meeting is on Monday!\" 25-25")
    }

    @Test
    fun aWebPasswordThatShowPasswordShowsIsStillSpelled() {
        screen.open("WebEditActivity", "Web edit fixture")
        check(VoiceTests.waitUntil(10_000) { webFieldShown("web_password") }) { "The web page's field did not appear" }
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

    // Tasks 6 and 7 add their tests above this line.
}
