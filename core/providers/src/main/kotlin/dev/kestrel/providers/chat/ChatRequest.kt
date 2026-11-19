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
) {
    override fun toString(): String =
        "ChatRequest(systemLength=${system.length}, messages=${messages.size}, tools=${tools.size}, " +
            "toolChoice=$toolChoice, reasoning=$reasoning, maxOutputTokens=$maxOutputTokens, " +
            "temperature=${if (temperature == null) "default" else "set"}, cacheKey=${if (cacheKey == null) "none" else "set"}, " +
            "jsonSchema=${if (jsonSchema == null) "none" else "set"})"
}

enum class ToolChoice { AUTO, REQUIRED, NONE }

enum class ReasoningLevel { MINIMAL, LOW, MEDIUM, HIGH }

/**
 * The eight request styles of spec 10.3. The first six are wire formats; VERTEX and AZURE are hosted platforms whose
 * adapters reuse a wire format per model (design D4).
 */
@Serializable
enum class RequestStyle { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC, GEMINI, OLLAMA, BEDROCK, VERTEX, AZURE }
