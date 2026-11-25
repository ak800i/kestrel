package dev.kestrel.conversation.local

import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.context.RecordText
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * The owner port of a memory request (design 8.1 of sub-project 7: "A memory request fills a record with its question,
 * the answer and the final sentence"): it passes everything to [owner] and traces what Kestrel says and asks and the
 * owner's answer, each redacted once here, because no run redacted them before. Silence and an interruption add no answer.
 */
class RecordedOwner(private val owner: OwnerPort, private val traced: suspend (TraceEntry) -> Unit) : OwnerPort {
    override suspend fun say(text: String) {
        traced(TraceEntry.Said(RecordText.of(text)))
        owner.say(text)
    }

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        traced(TraceEntry.Asked(RecordText.of(question)))
        val answer = owner.ask(question, kind, choices, highest)
        words(answer)?.let { traced(TraceEntry.Answered(RecordText.of(it))) }
        return answer
    }

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd = owner.confirm(confirmation)

    override suspend fun handOver(handOver: HandOver): HandOverEnd = owner.handOver(handOver)

    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd = owner.askPermission(permissions, why)

    override suspend fun gate(): Gate = owner.gate()

    override suspend fun leaveKestrel(): Boolean = owner.leaveKestrel()

    override fun toString(): String = "RecordedOwner($owner)"

    private companion object {
        fun words(answer: Answer): String? = when (answer) {
            Answer.Yes -> "yes"
            Answer.No -> "no"
            is Answer.Text -> answer.text
            is Answer.Number -> answer.n.toString()
            Answer.Silence, Answer.Interrupted -> null
        }
    }
}
