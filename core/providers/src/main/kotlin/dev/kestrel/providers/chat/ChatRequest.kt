package dev.kestrel.providers.chat

import dev.kestrel.providers.http.RequestMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One request to a model in Kestrel's own format (design D3). The adapters turn it into each provider's request style.
 * Sub-project 5 redacts the text before it builds a request (spec 12.6); the adapters send what they get.
 */
data class ChatRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val reasoning: ReasoningLevel? = null,
    val maxOutputTokens: Int,
    /** Null leaves the provider's default. */
    val temperature: Double? = null,
    /** Names the conversation's fixed part for the providers' prompt caches (spec 11.2). */
    val cacheKey: String? = null,
    /** The schema of a JSON reply, which JsonActions sets (design D7). */
    val jsonSchema: JsonObject? = null,
    val meta: RequestMeta = RequestMeta(),
    /**
     * The per-task text that follows the system prompt (design D5 of sub-project 5): the memory that the task needs and
     * the test-mode note. Each style puts it right after the system prompt, so that the tools and the system prompt stay
     * one cached prefix for every task.
     */
    val context: String? = null,
    /**
     * The index in [messages] of the last message that never changes again (design D3 and D5 of sub-project 5), where the
     * styles that take explicit cache marks put their last one; null for none.
     */
    val cacheAfter: Int? = null,
    /** Which of Copilot's cache fields the request may send (design D5 of sub-project 5). */
    val cacheFields: CacheFields = CacheFields.ALL,
) {
    override fun toString(): String =
        "ChatRequest(systemLength=${system.length}, messages=${messages.size}, tools=${tools.size}, " +
            "toolChoice=$toolChoice, reasoning=$reasoning, maxOutputTokens=$maxOutputTokens, " +
            "temperature=${if (temperature == null) "default" else "set"}, cacheKey=${if (cacheKey == null) "none" else "set"}, " +
            "jsonSchema=${if (jsonSchema == null) "none" else "set"}, context=${context?.let { "${it.length} chars" } ?: "none"}, " +
            "cacheAfter=${cacheAfter ?: "none"}, cacheFields=$cacheFields)"
}

/**
 * The cache fields that only VS Code's client shows Copilot to pass on (research r51), each of which the agent can switch
 * off for a model whose cache stays empty: Anthropic's `cache_control` on the context and on message `cacheAfter` (and
 * Bedrock's matching cache points), OpenAI's explicit mode with its breakpoints on Copilot's `/responses`, and
 * `copilot_cache_control` on Copilot's `/chat/completions` (design D5 of sub-project 5).
 */
@Serializable
data class CacheFields(val anthropicMarks: Boolean = true, val responsesExplicit: Boolean = true, val chatCopilotMarks: Boolean = true) {
    companion object {
        val ALL = CacheFields()
        val NONE = CacheFields(anthropicMarks = false, responsesExplicit = false, chatCopilotMarks = false)
    }
}

enum class ToolChoice { AUTO, REQUIRED, NONE }

enum class ReasoningLevel { MINIMAL, LOW, MEDIUM, HIGH }

/**
 * The eight request styles of spec 10.3. The first six are wire formats; VERTEX and AZURE are hosted platforms whose
 * adapters reuse a wire format per model (design D4).
 */
@Serializable
enum class RequestStyle { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC, GEMINI, OLLAMA, BEDROCK, VERTEX, AZURE }
