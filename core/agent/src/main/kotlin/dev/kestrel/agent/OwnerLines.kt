package dev.kestrel.agent

import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.agent.observe.ScreenText

/**
 * The lines that carry the owner's own words to the model (D8 of sub-project 5; design 7.3, 8.4 and 12.1 of
 * sub-project 7), the one vocabulary of the system prompt's rule: only the quoted words of a line that starts, after its
 * indent, with one of [MARKERS] are his, and every other text in quotes is data. Every such line is built here, by the run
 * and by the records of earlier commands alike. His words are escaped as screen text is, so that a line break in them can
 * never start a line of its own, such as a forged marker. Each function takes words that were redacted already.
 */
object OwnerLines {
    const val TASK = "The owner's task:"
    const val NEW_COMMAND = "The owner's new command:"
    const val GOAL = "The owner's goal:"
    const val SAID = "The owner said:"
    const val ADDED = "The owner added"

    val MARKERS: List<String> = listOf(TASK, NEW_COMMAND, GOAL, SAID, ADDED)

    /** The command of a run that starts a conversation, whole. */
    fun task(words: String): String = "$TASK ${quoted(words, null)}"

    /** A later command of a conversation, whole. */
    fun newCommand(words: String): String = "$NEW_COMMAND ${quoted(words, null)}"

    /** The first command of a goal that a new run continues, whole. */
    fun goal(words: String): String = "$GOAL ${quoted(words, null)}"

    /** What the owner said: his answer, whole, or with [limit] an earlier command's words, cut there. */
    fun said(words: String, limit: Int? = null): String = "$SAID ${quoted(words, limit)}"

    /**
     * His message while a goal acted, on [channel], [at] a point of the goal ("at step 3", "before the first step", "in an
     * earlier round"), or with no point in a record's line; whole, or with [limit] cut there.
     */
    fun added(channel: SteerChannel, at: String?, words: String, limit: Int? = null): String =
        listOfNotNull(ADDED, "(${channel.wire})", at).joinToString(" ") + ": " + quoted(words, limit)

    private fun quoted(words: String, limit: Int?): String = if (limit == null) ScreenText.quoteWhole(words) else ScreenText.quote(words, limit)
}
