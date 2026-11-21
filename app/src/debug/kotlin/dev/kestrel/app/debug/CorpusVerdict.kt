package dev.kestrel.app.debug

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.Commands

/**
 * Whether what Kestrel heard leads to the action that a corpus line's text leads to (design spec, section 16.4), for the
 * hooks voice-eval and voice-expect, so that the A71's lines through the air count as the emulator's do (plan 4E): 1 or
 * 0, and - for a group without instant commands. A conversation line must stay bare speech, which acts on nothing; a
 * dictation line must stay bare and type the same words and punctuation as the text; an appendix line must be the same
 * command, with the same of the scene's [labels] and the same of the [contacts].
 */
internal object CorpusVerdict {
    /** The groups of corpus\commands.txt whose lines are instant commands, speech that must stay bare, and dictation. */
    const val APPENDIX = "appendix"
    const val CONVERSATION = "conversation"
    const val DICTATION = "dictation"

    fun command(group: String, expected: String, heard: String, labels: List<String>, contacts: List<String>): String {
        val said = CommandParser.parse(heard)
        val right = when (group) {
            CONVERSATION -> said == null || said is Command.Bare
            DICTATION -> said is Command.Bare && Commands.sameDictation(expected, heard)
            APPENDIX -> {
                val wanted = CommandParser.parse(expected)
                wanted != null && said != null && Commands.sameAction(wanted, said, labels, contacts)
            }
            else -> return "-"
        }
        return if (right) "1" else "0"
    }
}
