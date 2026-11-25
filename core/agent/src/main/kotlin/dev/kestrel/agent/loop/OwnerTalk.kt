package dev.kestrel.agent.loop

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind

/**
 * A run's questions to the owner (design D2): each waits at the pause gate first, and the time spent waiting for the
 * owner does not count as work. A question that a touch or the wake word interrupted is asked again after the gate; the
 * third interruption in a row without a pause counts as no answer, so that the loop never asks forever, and ends the
 * row. Confirmations share the count. Plan 7A made the loops of `ask_user`, the yes-or-no questions and the limit's
 * question one.
 */
internal class Questions(private val run: TaskRun) {
    private var interruptions = 0

    /** Asks [question] until it gets an answer: never [Answer.Interrupted], whose third time in a row is [Answer.Silence]. */
    suspend fun ask(question: String, kind: AnswerKind): Answer {
        while (true) {
            run.gate()
            val answer = run.limits.waiting { run.ports.owner.ask(question, kind) }
            if (answer == Answer.Interrupted && !interrupted()) continue
            countAgain()
            return if (answer == Answer.Interrupted) Answer.Silence else answer
        }
    }

    /** A yes-or-no question: true only for yes. */
    suspend fun yes(question: String): Boolean = ask(question, AnswerKind.YES_NO) == Answer.Yes

    /** A question or a confirmation was interrupted; true at the third interruption in a row, which counts as no answer. */
    fun interrupted(): Boolean = ++interruptions >= MAX_INTERRUPTIONS

    /** The count of interruptions in a row starts again: an answer came, or a pause ended. */
    fun countAgain() {
        interruptions = 0
    }

    override fun toString(): String = "Questions(interruptions=$interruptions)"

    private companion object {
        const val MAX_INTERRUPTIONS = 3
    }
}
