package dev.kestrel.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameActionTest {
    private val scene = listOf("Save", "Safe", "Send", "Send later", "Message from Ana", "Delete", "Settings")

    private fun same(expected: String, heard: String, labels: List<String> = scene): Boolean =
        Commands.sameAction(checkNotNull(CommandParser.parse(expected)), checkNotNull(CommandParser.parse(heard)), labels)

    @Test
    fun theSameCommandWithItsCaseAndPunctuationIsTheSame() {
        assertTrue(same("Scroll down.", "scroll down"))
        assertTrue(same("Tap number 12.", "Tap number twelve."))
        assertTrue(same("Turn the volume up.", "Turn the volume up"))
        assertFalse(same("Scroll down.", "Scroll up."))
        assertFalse(same("Turn off Bluetooth.", "Turn on Bluetooth."))
        assertFalse(same("Show numbers.", "Show labels."))
    }

    @Test
    fun aLabelIsTheSameWhenTheMatcherFindsTheSameLabel() {
        assertTrue(same("Tap Message from Ana.", "Tap message from Anna."))
        assertTrue(same("Tap Send later.", "tap send later"))
        assertFalse(same("Tap Save.", "Tap Safe."))
        assertFalse(same("Tap Send.", "Tap Send later."))
        // Without a label for the expected words, the words themselves must match.
        assertTrue(same("Long-press the second photo.", "Long press the second photo", emptyList()))
        assertFalse(same("Long-press the second photo.", "Long press the second phone", emptyList()))
    }

    @Test
    fun aBareLabelNeedsTheSameStrictMatchAndANumberTheSameNumber() {
        assertTrue(same("Send later.", "Send later"))
        assertFalse(same("Send later.", "Sent letter"))
        assertTrue(same("Seven.", "7"))
        assertFalse(same("Seven.", "11"))
    }

    @Test
    fun payloadsAndNamesNeedNoWordErrors() {
        assertTrue(Commands.sameAction(TextCommand.Type("hello there"), TextCommand.Type("Hello there."), scene))
        assertFalse(Commands.sameAction(TextCommand.Type("hello there"), TextCommand.Type("hello their"), scene))
        assertTrue(same("Call Mom.", "call mom"))
        assertFalse(same("Call Mom.", "Call Tom."))
        // The recogniser cannot write "ć"; the contact matcher still finds the same contact, as Kestrel would.
        assertTrue(same("Call Ana Petrović.", "Call Anna Petrovich.", listOf("Ana Petrović", "Mom")))
        assertTrue(same("Call Ana Petrović.", "Call Ana Petrovic."))
        assertFalse(same("Call Ana mobile.", "Call Anna.", listOf("Ana Petrović", "Mom")))
        assertTrue(same("Call 064 123 4567.", "call zero six four one two three four five six seven"))
        assertTrue(same("Open settings.", "Open setting.", listOf("Settings", "Clock")))
        assertFalse(same("Open work Gmail.", "Open Gmail."))
    }

    @Test
    fun aContactsNameCountsAmongTheContactsThatTheLineNames() {
        val contacts = listOf("Ana Petrović", "Mom")
        fun call(expected: String, heard: String, known: List<String> = contacts) =
            Commands.sameAction(checkNotNull(CommandParser.parse(expected)), checkNotNull(CommandParser.parse(heard)), scene, known)

        // The recogniser writes "Anna" for "Ana", whom Kestrel finds by her first name, as it would among the owner's contacts.
        assertTrue(call("Call Ana mobile.", "Call Anna Mobile."))
        assertTrue(call("Call Ana's work number.", "Call Anna's work number."))
        assertFalse(call("Call Ana mobile.", "Call on a mobile."))
        assertFalse(call("Call Mom.", "Call Tom."))
        // The scene's labels name no one to call, so without the contacts the words must be the same.
        assertFalse(call("Call Ana mobile.", "Call Anna mobile.", emptyList()))
    }

    @Test
    fun dictationIsTheSameWhenItTypesTheSameWordsAndMarks() {
        assertTrue(Commands.sameDictation("hello comma how are you question mark", "Hello, comma, how are you? Question mark."))
        assertFalse(Commands.sameDictation("hello comma how are you question mark", "Hello, how are you? Question mark."))
        assertFalse(Commands.sameDictation("see you at five period", "see you at nine period"))
    }
}
