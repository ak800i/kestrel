package dev.kestrel.app.orchestrator

import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer

/**
 * The spoken answers that the conversation's questions take (design D2 of sub-project 5; design 11 of sub-project 7),
 * read on Normalizer's words, so that "Confirm." counts like "confirm" and "Kestrel, continue" like "continue". A
 * confirmation goes ahead on "confirm", "yes" or "send it"; a yes-or-no question, a limit's "Continue?", the switch to a
 * past conversation and "Continue the goal?" take "yes", "continue" or "go on"; "Delete the history?" takes only "yes",
 * because nothing else may delete the owner's history (plan 4E's rule); all of them take "no". The polite words
 * and the name may come with them. A free question and the screenshot question take any words, and a pick a number.
 * Anything else is no answer, and the question goes on.
 */
internal object VoiceAnswers {
    private val FILLERS = setOf("please", "now", "ok", "okay", "thanks", "thank", "you", "oh", "well")
    private val CONFIRM = setOf("confirm", "yes", "send it")
    private val GO_ON = setOf("yes", "continue", "go on")
    private val YES = setOf("yes")

    /** The answer to a question of [kind] in the transcript [text], which Requests read as [request], or null for none. */
    fun read(kind: QuestionKind, text: String, named: Boolean, request: Request): ChannelAnswer? = when (kind) {
        QuestionKind.CONFIRM -> yesOrNo(text, named, CONFIRM)
        QuestionKind.YES_NO, QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT, QuestionKind.SWITCH -> yesOrNo(text, named, GO_ON)
        QuestionKind.DELETE_HISTORY -> yesOrNo(text, named, YES)
        QuestionKind.PICK -> SpokenNumbers.answer(text.trim())?.let { ChannelAnswer.Pick(it) }
        QuestionKind.FREE, QuestionKind.SCREENSHOT -> when (request) {
            is Request.Task -> request.text
            is Request.Other -> request.text
            else -> text.trim()
        }.takeIf { it.isNotEmpty() }?.let { ChannelAnswer.Text(it) }
    }

    private fun phrase(text: String, named: Boolean): String =
        Normalizer.words(text).filter { it !in FILLERS && Requests.read(it, named) != Request.Name }.joinToString(" ")

    private fun yesOrNo(text: String, named: Boolean, yes: Set<String>): ChannelAnswer? = when (phrase(text, named)) {
        in yes -> ChannelAnswer.Yes
        "no" -> ChannelAnswer.No
        else -> null
    }
}
