package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.Observation
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.ToolSpec
import dev.kestrel.providers.http.RequestMeta

/** A tool call's result text, before the step's observation or data lines are added to it (design D3 and D6). */
data class ResultText(val callId: String, val name: String, val text: Redacted, val failed: Boolean = false) {
    override fun toString(): String = "ResultText(nameLength=${name.length}, text=$text, failed=$failed)"
}

/**
 * One step as it enters the conversation (design D3): the model's [reply] exactly as it came, one [results] entry for each
 * of its tool calls, and what the executed call [observedCall] brought: a new [observation] or a data tool's [data] lines,
 * which go below its result line and shrink to [summary] later. A [screenshot] follows as a user message, because tool
 * results carry text only; a [reprompt] follows as a user message when the reply had no call that Kestrel could use.
 */
class Step(
    val number: Int,
    val reply: ChatMessage.Assistant,
    val results: List<ResultText>,
    val observedCall: String? = null,
    val observation: Observation? = null,
    val data: Redacted? = null,
    val summary: Redacted? = null,
    val screenshot: Part.Image? = null,
    val reprompt: Redacted? = null,
) {
    init {
        val calls = reply.toolCalls.map { it.id }
        require(results.map { it.callId } == calls) { "A step needs one result for each tool call, in order" }
        require(observation == null || data == null) { "A step brings an observation or data lines, not both" }
        if (observation != null || data != null) {
            require(observedCall != null && observedCall in calls) { "The observed call must be one of the step's calls" }
            require(summary != null) { "A step with an observation or data lines needs its one-line summary" }
        }
    }

    override fun toString(): String =
        "Step(number=$number, calls=${results.size}, observation=${observation != null}, data=${data != null}, " +
            "screenshot=${screenshot != null}, reprompt=${reprompt != null})"
}

/**
 * The conversation of one task (design D3 and D5; spec 11.2). Every request has the same [system] prompt and the same
 * [tools]; the per-task [context] goes into `ChatRequest.context`. The messages are the [task] message, the first
 * observation in a user message of its own, and then the steps. Only the two newest observations stay in full: the
 * second-newest is relabelled once as an earlier screen whose numbers are no longer valid, and every older step shrinks
 * once to its one-line summary, its screenshot with it. So the conversation has a stable region that never changes once
 * written (the task message and the shrunk steps) and a tail, and `cacheAfter` names the stable region's last message.
 *
 * Kestrel keeps every assistant message as it came, with its reasoning state, except that for a model with Claude's
 * signed thinking outside Copilot ([signedThinking]) the reasoning of the assistant messages in the stable region is
 * removed at the moment of the shrink (D32 item 9), and [growOnly] undoes the rewrites that no accepted request carried
 * and stops every later one. A step is only ever removed whole (D32 item 8): for a local Ollama model whose [budget]
 * (tokens, with room for the reply) would be passed, the oldest shrunk steps go and one line says how many (D32 item 10).
 * The loop sets [budget] before each request to the window of the model that gets it, so that a switch of models
 * changes it (D7).
 * Every text in here is [Redacted] or an [Observation], which the observation builder redacted, so nothing is redacted
 * twice (D17).
 */
class Conversation(
    private val system: String,
    private val tools: List<ToolSpec>,
    private val context: Redacted?,
    private val task: Redacted,
    first: Observation,
    private val taskId: String,
    private val signedThinking: Boolean = false,
    internal var budget: Int? = null,
    private val cacheKey: String? = CACHE_KEY,
) {
    private enum class State { FULL, EARLIER, SHRUNK }

    private class Entry(
        val number: Int,
        var reply: ChatMessage.Assistant?,
        val results: List<ResultText>,
        val observedCall: String?,
        val note: Redacted?,
        val observation: Observation?,
        val data: Redacted?,
        val summary: Redacted?,
        val screenshot: Part.Image?,
        val reprompt: Redacted?,
    ) {
        var state = State.FULL
    }

    private class Saved(val entry: Entry, val state: State, val reply: ChatMessage.Assistant?)

    private val entries = mutableListOf(
        Entry(0, null, emptyList(), null, null, first, null, Redacted.kestrel(first.firstScreen()), null, null),
    )
    private var leftOut = 0
    private var growing = false

    // The entries as they were before the first rewrite that no provider has accepted yet: a new step's reply shows that
    // the request built before it was accepted, so its rewrites are known good.
    private var unconfirmed: List<Saved>? = null

    /** The newest observation, whose numbers the model may use. */
    val newestObservation: Observation get() = entries.last { it.observation != null }.observation!!

    /** Whether the conversation only grows from now on (D32 item 9's fallback). */
    val growsOnly: Boolean get() = growing

    fun add(step: Step) {
        unconfirmed = null
        entries += Entry(
            step.number, step.reply, step.results, step.observedCall, null, step.observation, step.data, step.summary,
            step.screenshot, step.reprompt,
        )
        if (step.observation != null) settle()
    }

    /** A new observation without a reply, after the task was paused and resumed (design D2), in a user message. */
    fun addObservation(number: Int, note: Redacted, observation: Observation) {
        val summary = Redacted.kestrel(observation.firstScreen().replaceFirst(FIRST_SCREEN, AFTER_PAUSE))
        entries += Entry(number, null, emptyList(), null, note, observation, null, summary, null, null)
        settle()
    }

    /**
     * Nothing is rewritten from now on, because a provider refused the history after a shrink (D32 item 9): the shrinks,
     * removed reasoning and relabelled observations that no accepted request has carried yet are undone, so that the
     * retry is the last accepted request with the new steps appended, and from then on no step shrinks or loses its
     * reasoning, and no observation is relabelled, since newer Claude models also refuse signed thinking after a change
     * of an earlier tool result. `ToolArguments` still checks every number against the newest observation.
     */
    fun growOnly() {
        growing = true
        unconfirmed?.forEach { saved ->
            saved.entry.state = saved.state
            saved.entry.reply = saved.reply
        }
        unconfirmed = null
    }

    /**
     * The request for model reply number [step] (1 for the task's first), or null when even the fixed part and the two
     * newest observations do not fit the model's [budget] (D32 item 10).
     */
    fun request(toolChoice: ToolChoice, reasoning: ReasoningLevel?, step: Int): ChatRequest? {
        while (true) {
            val limit = budget
            val (messages, cacheAfter) = messages()
            val request = ChatRequest(
                system = system,
                messages = messages,
                tools = tools,
                toolChoice = toolChoice,
                reasoning = reasoning,
                maxOutputTokens = MAX_OUTPUT_TOKENS,
                cacheKey = cacheKey,
                meta = RequestMeta(taskId = taskId, agentStep = step > 1),
                context = context?.text,
                cacheAfter = cacheAfter,
            )
            if (limit == null || tokens(request) + MAX_OUTPUT_TOKENS <= limit * BUDGET_SHARE) return request
            if (!leaveOutOldest()) return null
        }
    }

    // After a new observation: the second-newest is relabelled once, and every step before it shrinks once.
    private fun settle() {
        val observed = entries.indices.filter { entries[it].observation != null }
        if (observed.size < 2) return
        if (growing) return
        if (unconfirmed == null) unconfirmed = entries.map { Saved(it, it.state, it.reply) }
        val second = observed[observed.size - 2]
        if (entries[second].state == State.FULL) entries[second].state = State.EARLIER
        var shrunk = false
        for (index in 0 until second) {
            val entry = entries[index]
            if (entry.state != State.SHRUNK && (entry.observation != null || entry.data != null || entry.screenshot != null)) {
                entry.state = State.SHRUNK
                shrunk = true
            }
        }
        if (shrunk && signedThinking) {
            for (index in 0 until second) entries[index].reply = entries[index].reply?.let(::withoutSignedThinking)
        }
    }

    // The first entry of the tail: the one with the second-newest observation, or the newest when there is only one.
    private fun boundary(): Int {
        val observed = entries.indices.filter { entries[it].observation != null }
        return if (observed.size >= 2) observed[observed.size - 2] else observed.last()
    }

    private fun messages(): Pair<List<ChatMessage>, Int> {
        val messages = mutableListOf<ChatMessage>(ChatMessage.User(listOf(Part.Text(task.text))))
        val tail = boundary()
        var cacheAfter = 0
        for ((index, entry) in entries.withIndex()) {
            if (index == tail) cacheAfter = messages.size - 1
            messages += messagesOf(entry)
            if (index == 0 && leftOut > 0) messages += user("(" + leftOut + " earlier steps left out.)")
        }
        return messages to cacheAfter
    }

    private fun messagesOf(entry: Entry): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val reply = entry.reply
        if (reply == null) {
            val body = when (entry.state) {
                State.SHRUNK -> entry.summary!!.text
                State.EARLIER -> listOfNotNull(entry.note?.text, entry.observation!!.earlier()).joinToString("\n")
                State.FULL -> listOfNotNull(entry.note?.text, entry.observation!!.text).joinToString("\n")
            }
            messages += user(body)
        } else {
            messages += reply
            for (result in entry.results) {
                val text = if (result.callId == entry.observedCall) observedText(entry, result) else result.text.text
                messages += ChatMessage.ToolResult(result.callId, result.name, text, result.failed)
            }
        }
        entry.screenshot?.let { image ->
            messages += if (entry.state == State.SHRUNK) user("(The screenshot of step ${entry.number} was here.)") else ChatMessage.User(listOf(image))
        }
        entry.reprompt?.let { messages += user(it.text) }
        return messages
    }

    private fun observedText(entry: Entry, result: ResultText): String {
        val body = when {
            entry.state == State.SHRUNK -> return entry.summary!!.text
            entry.observation != null && entry.state == State.EARLIER -> entry.observation.earlier()
            entry.observation != null -> entry.observation.text
            else -> entry.data!!.text
        }
        return result.text.text + "\n" + body
    }

    // Removes the oldest step of the stable region after the first observation, whole (D32 items 8 and 10).
    private fun leaveOutOldest(): Boolean {
        val tail = boundary()
        if (tail <= 1) return false
        entries.removeAt(1)
        leftOut++
        return true
    }

    private fun tokens(request: ChatRequest): Int {
        var total = ScreenText.tokens(request.system) + ScreenText.tokens(request.context.orEmpty())
        for (tool in request.tools) total += ScreenText.tokens(tool.name + tool.description + tool.parameters.toString())
        for (message in request.messages) {
            total += when (message) {
                is ChatMessage.User -> message.parts.sumOf { if (it is Part.Text) ScreenText.tokens(it.text) else IMAGE_TOKENS }
                is ChatMessage.Assistant -> message.parts.sumOf {
                    when (it) {
                        is AssistantPart.Text -> ScreenText.tokens(it.text)
                        is AssistantPart.ToolCall -> ScreenText.tokens(it.name + it.arguments.toString())
                        is AssistantPart.Reasoning -> ScreenText.tokens(it.state.payload.toString())
                    }
                }
                is ChatMessage.ToolResult -> ScreenText.tokens(message.text)
            }
        }
        return total
    }

    private fun user(text: String) = ChatMessage.User(listOf(Part.Text(text)))

    override fun toString(): String = "Conversation(steps=${entries.size - 1}, leftOut=$leftOut, growsOnly=$growing)"

    companion object {
        /** Both roles' limit; the limit includes reasoning on most styles, and unused tokens cost nothing (D3). */
        const val MAX_OUTPUT_TOKENS = 4_096

        /** `prompt_cache_key` for OpenAI's own API: one key for every task, so that tasks share the cached prefix (D5). */
        const val CACHE_KEY = "kestrel-agent"

        /**
         * A local Ollama model's budget: its window, at most 32,768 tokens. The prompt and the reply's [MAX_OUTPUT_TOKENS]
         * stay below 90 % of it (D32 item 10), because Ollama's `num_ctx` holds both and the reply cannot shift the prompt.
         */
        const val OLLAMA_BUDGET = 32_768
        private const val BUDGET_SHARE = 0.9
        private const val IMAGE_TOKENS = 1_000
        private const val FIRST_SCREEN = "First screen"
        private const val AFTER_PAUSE = "Screen after the pause"

        /** Claude's signed thinking comes back in these styles only (Anthropic's wire, also on Vertex and Foundry, and Bedrock). */
        private val SIGNED_STYLES = setOf(RequestStyle.ANTHROPIC, RequestStyle.BEDROCK)

        private fun withoutSignedThinking(reply: ChatMessage.Assistant): ChatMessage.Assistant =
            ChatMessage.Assistant(reply.parts.filterNot { it is AssistantPart.Reasoning && it.state.style in SIGNED_STYLES })

        /** The budget of a local Ollama model with [window] tokens (null: unknown), or null for every other model. */
        fun budget(localOllama: Boolean, window: Int?): Int? =
            if (localOllama) minOf(window ?: OLLAMA_BUDGET, OLLAMA_BUDGET) else null
    }
}
