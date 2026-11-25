package dev.kestrel.agent.loop

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind

/**
 * What a run says to the owner and asks him (design D2): each waits at the pause gate first, and the time spent waiting
 * for the owner does not count as work. A question that a touch or the wake word interrupted is asked again after the
 * gate; the third interruption in a row without a pause counts as no answer, so that the loop never asks forever, and ends
 * the row. Every question of the run goes through [ask], also `look_closer`'s and the limit's, so one rule holds for all
 * of them; confirmations share the count.
 */
internal class OwnerTalk(private val run: TaskRun) {
    private var interruptions = 0

    /** Says [text] to the owner. */
    suspend fun say(text: String) {
        run.gate()
        run.limits.waiting { run.ports.owner.say(text) }
    }

    /**
     * Asks [question], with a pick's [choices] and [highest] number, until it gets an answer: never [Answer.Interrupted],
     * whose third time in a row is [Answer.Silence].
     */
    suspend fun ask(question: String, kind: AnswerKind, choices: List<String> = emptyList(), highest: Int = 0): Answer {
        while (true) {
            run.gate()
            val answer = run.limits.waiting { run.ports.owner.ask(question, kind, choices, highest) }
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

    override fun toString(): String = "OwnerTalk(interruptions=$interruptions)"

    private companion object {
        const val MAX_INTERRUPTIONS = 3
    }
}
