package dev.kestrel.agent.loop

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * One run's owner port (plan 7A; design 8.1 of sub-project 7): it passes everything to [owner] and traces the dialogue for
 * the command's record through [trace] as it happens. What Kestrel says, a hand-over's sentence and a permission
 * request's reason are said; each question and confirmation is asked before it is asked, and the owner's answer is traced
 * after it: his words, a number, yes or no. Silence, an interruption, a timeout and the end of a hand-over add no answer.
 */
internal class TracingOwner(private val owner: OwnerPort, private val trace: RunTrace) : OwnerPort {
    override suspend fun say(text: String) {
        trace.said(text)
        owner.say(text)
    }

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        trace.asked(question)
        val answer = owner.ask(question, kind, choices, highest)
        words(answer)?.let { trace.answered(it) }
        return answer
    }

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd {
        trace.asked(confirmation.question)
        val end = owner.confirm(confirmation)
        when (end) {
            ConfirmEnd.YES -> trace.answered(YES)
            ConfirmEnd.NO -> trace.answered(NO)
            ConfirmEnd.TIMEOUT, ConfirmEnd.INTERRUPTED -> Unit
        }
        return end
    }

    override suspend fun handOver(handOver: HandOver): HandOverEnd {
        trace.said(handOver.text)
        return owner.handOver(handOver)
    }

    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        trace.said(why)
        return owner.askPermission(permissions, why)
    }

    override suspend fun gate(): Gate = owner.gate()

    override suspend fun leaveKestrel(): Boolean = owner.leaveKestrel()

    override fun toString(): String = "TracingOwner($owner)"

    private companion object {
        const val YES = "yes"
        const val NO = "no"

        fun words(answer: Answer): String? = when (answer) {
            Answer.Yes -> YES
            Answer.No -> NO
            is Answer.Text -> answer.text
            is Answer.Number -> answer.n.toString()
            Answer.Silence, Answer.Interrupted -> null
        }
    }
}
