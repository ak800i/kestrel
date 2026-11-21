package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.ParsedCall
import dev.kestrel.agent.tools.ToolArguments
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.safety.Vault

/** What Kestrel does with one reply of the model (design D4). */
sealed interface Reply {
    /** The reply's first call [call] runs; [notRun] answers each other call of the reply (D32 item 6). */
    data class Act(val callId: String, val name: String, val call: AgentCall, val notRun: List<ResultText>) : Reply {
        override fun toString(): String = "Act(call=$call, notRun=${notRun.size})"
    }

    /**
     * No call that Kestrel can use: [results] answer every call of the reply, the first (or each, beside a call that the
     * adapter could not use) with what was wrong, and [reprompt]
     * follows when the reply had no call to answer (D32 item 7). It counts as one invalid tool call (spec 11.7, 14.2).
     */
    data class Invalid(val results: List<ResultText>, val reprompt: Redacted?) : Reply {
        override fun toString(): String = "Invalid(results=${results.size}, reprompt=${reprompt != null})"
    }
}

/**
 * The rules for the model's replies (design D4; D32 items 6 and 7): Kestrel runs only the first tool call of a reply and
 * answers every other call with [NOT_RUN], which does not count as an invalid call. A reply with text and no call, a call
 * that the adapter could not use, a call whose arguments break its schema or name a number that the newest observation
 * does not have, and a reply cut by `MAX_TOKENS` or stopped as `FILTERED` are invalid: a call with an ID gets a failed
 * result with a fixed sentence (never the model's text), and a reply without one gets a re-prompt. A reply with a call that
 * the adapter could not use is invalid also when it holds usable calls: none of them runs, and each gets a failed result.
 */
object ToolCalls {
    const val NOT_RUN = "Not run: Kestrel runs one action per step."
    const val REPROMPT = "Reply with exactly one tool call."
    const val CUT = "The reply was cut off before it ended."
    const val FILTERED = "The provider's filter stopped the reply."
    private const val OTHER_UNUSABLE = "Not run: another call of this reply could not be used."

    fun read(response: ChatResponse, table: ObservationTable, vault: Vault): Reply {
        val calls = response.message.toolCalls
        val stopped = when (response.stop) {
            StopReason.MAX_TOKENS -> CUT
            StopReason.FILTERED -> FILTERED
            else -> null
        }
        if (stopped != null) return invalid(response, stopped)
        // The adapter leaves an unusable call out of the message and keeps the others, so a usable call beside it may be the
        // model's second one: no call of such a reply runs.
        val unusable = response.invalidToolCall?.let { sentence(it.kind) }
        if (unusable != null) return invalid(response, if (calls.isEmpty()) unusable else "$OTHER_UNUSABLE $unusable")
        val first = calls.firstOrNull() ?: return invalid(response, null)
        val notRun = calls.drop(1).map { ResultText(it.id, it.name, Redacted.kestrel(NOT_RUN), failed = true) }
        return when (val parsed = ToolArguments.parse(first.name, first.arguments, table, vault)) {
            is ParsedCall.Valid -> Reply.Act(first.id, first.name, parsed.call, notRun)
            is ParsedCall.Invalid ->
                Reply.Invalid(listOf(ResultText(first.id, first.name, Redacted.kestrel(parsed.reason), failed = true)) + notRun, null)
        }
    }

    // Every call gets [what] as its failed result; a reply without calls gets [what] and the re-prompt as a user message.
    private fun invalid(response: ChatResponse, what: String?): Reply.Invalid {
        val calls = response.message.toolCalls
        if (calls.isEmpty()) return Reply.Invalid(emptyList(), Redacted.kestrel(listOfNotNull(what, REPROMPT).joinToString(" ")))
        return Reply.Invalid(calls.map { ResultText(it.id, it.name, Redacted.kestrel(what ?: REPROMPT), failed = true) }, null)
    }

    /** Kestrel's sentence for a call that the adapter could not use; the model's own text is never repeated. */
    fun sentence(kind: InvalidKind): String = when (kind) {
        InvalidKind.ARGUMENTS_NOT_OBJECT -> "The call's arguments were not a JSON object."
        InvalidKind.UNKNOWN_TOOL -> "That tool does not exist."
        InvalidKind.MISSING_ARGUMENT -> "The call lacked a required argument."
        InvalidKind.NO_ACTION -> "The reply named no action."
        InvalidKind.BAD_JSON -> "The call was not valid JSON."
    }
}

/**
 * The tool choice per model (design D4; r59): `REQUIRED` asks for exactly one call where the style allows it; a model that
 * runs with a reasoning level gets `AUTO`; and after a 400 that names the tool choice, the same request goes once more
 * with `AUTO`, which that model keeps for the rest of the task.
 */
class ToolChoices {
    private val automatic = mutableSetOf<String>()

    fun choice(modelId: String, reasoning: ReasoningLevel?): ToolChoice =
        if (reasoning != null || modelId in automatic) ToolChoice.AUTO else ToolChoice.REQUIRED

    /** Whether the request that got [error] with [sent] goes once more with `AUTO`; from now on [modelId] gets `AUTO`. */
    fun fallBack(modelId: String, sent: ToolChoice, error: ProviderError): Boolean =
        error is ProviderError.BadRequest && error.toolChoice && sent != ToolChoice.AUTO && automatic.add(modelId)

    override fun toString(): String = "ToolChoices(automatic=${automatic.size})"
}
