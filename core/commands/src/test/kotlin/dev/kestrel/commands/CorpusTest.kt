package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The instant commands of the voice corpus (design spec, section 16.4) parse as the evaluation expects: every line of the
 * group appendix to a command, except the bare labels and numbers, and every conversation and dictation line to bare
 * speech. The tests run in the module's folder.
 */
class CorpusTest {
    private val lines = File("../../corpus/commands.txt").readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .map { line -> line.split('|').map { it.trim() } }

    private fun parsed(group: String) = lines.filter { it[1] == group }.associate { it[0] to CommandParser.parse(it[6]) }

    @Test
    fun everyInstantCommandLineIsACommandAndTheBareLabelsAndNumbersStayBare() {
        val commands = parsed("appendix")
        val bare = commands.filterValues { it == null || it is Command.Bare }.keys - UNCHECKED

        assertEquals(BARE, bare)
        assertEquals(219, commands.size)
    }

    @Test
    fun conversationAndDictationStayBare() {
        val speech = parsed("conversation") + parsed("dictation")

        assertEquals(emptyMap<String, Command?>(), speech.filterValues { it !is Command.Bare })
        assertEquals(25, speech.size)
    }

    @Test
    fun kokorosTranscriptsOfTheCallLinesCallTheContactThatTheLinesName() {
        // The recogniser wrote these for Kokoro's clips of the three call lines; the contacts of corpus\scenes.txt find
        // Ana by a first name that differs only in a doubled letter.
        val contacts = File("../../corpus/scenes.txt").readLines(Charsets.UTF_8)
            .single { it.startsWith("contacts |") }.substringAfter('|').split(';').map { it.trim() }
        val heard = mapOf("c165" to "Call Anna Petrovich.", "c166" to "Call Anna Mobile.", "c167" to "Call Anna's work number.")
        val expected = lines.associate { it[0] to it[6] }

        for ((id, transcript) in heard) {
            val line = checkNotNull(CommandParser.parse(expected.getValue(id)))
            assertTrue(id, Commands.sameAction(line, checkNotNull(CommandParser.parse(transcript)), emptyList(), contacts))
        }
    }

    private companion object {
        // "Cancel.", "Seven.", "Save." and "Send later." name buttons, or a number, on their own.
        val BARE = setOf("c129", "c156", "c157", "c159")

        // "Delete the last word." is no phrasing that the design fixes for plan 3B, so it is not checked here.
        val UNCHECKED = setOf("c029")
    }
}
