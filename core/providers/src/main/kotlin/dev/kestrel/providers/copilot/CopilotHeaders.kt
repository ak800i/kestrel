package dev.kestrel.providers.copilot

import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestHeaders

/**
 * The headers of Copilot's requests (design D12; research r27): the runtime headers of `overrides.json`, `x-initiator`,
 * and `Copilot-Vision-Request: true` when the request has an image. `x-initiator` is `agent` for an agent's step after a
 * task's first request ([dev.kestrel.providers.http.RequestMeta.agentStep]) or when the last message is a tool result,
 * and `user` otherwise. Kestrel sends no `Openai-Intent`. [extra] are the debug hook's headers for a retry in the owner's
 * session; release code passes none.
 */
object CopilotHeaders {
    fun forRequests(runtime: Map<String, String>, extra: Map<String, String> = emptyMap()): RequestHeaders = RequestHeaders { request ->
        val vision = if (hasImage(request)) mapOf("Copilot-Vision-Request" to "true") else emptyMap()
        runtime + ("x-initiator" to initiator(request)) + vision + extra
    }

    fun initiator(request: ChatRequest): String =
        if (request.meta.agentStep || request.messages.lastOrNull() is ChatMessage.ToolResult) "agent" else "user"

    fun hasImage(request: ChatRequest): Boolean =
        request.messages.any { message -> message is ChatMessage.User && message.parts.any { it is Part.Image } }
}
