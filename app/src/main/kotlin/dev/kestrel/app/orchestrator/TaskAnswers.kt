package dev.kestrel.app.orchestrator

import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.text.Normalizer

/**
 * The answers that a task's question takes (design D2 of sub-project 5), read on Normalizer's words, so that "Confirm."
 * counts like "confirm" and "Kestrel, continue" like "continue". A confirmation goes ahead on "confirm", "yes" or "send
 * it"; a yes-or-no question and "Continue the task?" take "yes", "continue" or "go on"; both take "no". The polite words
 * and the name may come with them. A free question takes any words, and a number question a number. Anything else is no
 * answer, and the question goes on.
 */
internal object TaskAnswers {
    const val YES = "yes"
    const val NO = "no"

    private val FILLERS = setOf("please", "now", "ok", "okay", "thanks", "thank", "you", "oh", "well")
    private val CONFIRM = setOf("confirm", "yes", "send it")
    private val GO_ON = setOf("yes", "continue", "go on")

    /** The answer to a question of [kind] in the transcript [text], or null when it gives none. */
    fun read(kind: TaskAsk, text: String, named: Boolean, request: Request): String? = when (kind) {
        TaskAsk.CONFIRM -> yesOrNo(text, named, CONFIRM)
        TaskAsk.YES_NO, TaskAsk.CONTINUE -> yesOrNo(text, named, GO_ON)
        TaskAsk.PICK_NUMBER -> text.trim().takeIf { SpokenNumbers.answer(it) != null }
        TaskAsk.FREE -> when (request) {
            is Request.Task -> request.text
            is Request.Other -> request.text
            else -> text.trim()
        }.takeIf { it.isNotEmpty() }
    }

    /** Whether [text] is "yes", with the polite words and the name, and nothing else ("Delete the history?"). */
    fun saysYes(text: String, named: Boolean): Boolean = phrase(text, named) == YES

    private fun phrase(text: String, named: Boolean): String =
        Normalizer.words(text).filter { it !in FILLERS && Requests.read(it, named) != Request.Name }.joinToString(" ")

    private fun yesOrNo(text: String, named: Boolean, yes: Set<String>): String? {
        return when (phrase(text, named)) {
            in yes -> YES
            NO -> NO
            else -> null
        }
    }
}
