package dev.kestrel.providers.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A message of a conversation (design D3). Their toString prints only kinds, counts and lengths (design D22). */
sealed interface ChatMessage {
    /** The owner's turn and the agent's observations; the only place for images, which every style accepts there. */
    data class User(val parts: List<Part>) : ChatMessage {
        override fun toString(): String = "User(parts=$parts)"
    }

    data class Assistant(val parts: List<AssistantPart>) : ChatMessage {
        val toolCalls: List<AssistantPart.ToolCall> get() = parts.filterIsInstance<AssistantPart.ToolCall>()

        override fun toString(): String = "Assistant(parts=$parts)"
    }

    /** The result of the tool call [callId]; ChatRequests.check requires one for every call (design D3). */
    data class ToolResult(val callId: String, val name: String, val text: String, val failed: Boolean = false) : ChatMessage {
        override fun toString(): String =
            "ToolResult(nameLength=${name.length}, callIdLength=${callId.length}, textLength=${text.length}, failed=$failed)"
    }
}

sealed interface Part {
    data class Text(val text: String) : Part {
        override fun toString(): String = "Text(length=${text.length})"
    }

    /** A PNG or JPEG picture, such as the agent's screenshot (spec 11.6). */
    class Image(val type: ImageType, val bytes: ByteArray) : Part {
        override fun equals(other: Any?): Boolean = other is Image && other.type == type && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * type.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Image(type=$type, bytes=${bytes.size})"
    }
}

enum class ImageType(val mime: String) { PNG("image/png"), JPEG("image/jpeg") }

sealed interface AssistantPart {
    data class Text(val text: String) : AssistantPart {
        override fun toString(): String = "Text(length=${text.length})"
    }

    /** A model can name a tool that Kestrel does not have (design D3's UNKNOWN_TOOL), so the name is only measured. */
    data class ToolCall(val id: String, val name: String, val arguments: JsonObject) : AssistantPart {
        override fun toString(): String = "ToolCall(nameLength=${name.length}, idLength=${id.length}, arguments=${arguments.size})"
    }

    data class Reasoning(val state: ReasoningState) : AssistantPart {
        override fun toString(): String = "Reasoning($state)"
    }
}

/**
 * Reasoning that a provider wants back on the next request, kept opaque (design D3 and D5). Signed or encrypted state goes
 * back only to the same style, provider and model; plain reasoning text of Chat Completions goes back to the same provider.
 */
data class ReasoningState(val style: RequestStyle, val providerId: String, val modelId: String, val payload: JsonElement) {
    override fun toString(): String =
        "ReasoningState(style=$style, provider=$providerId, modelIdLength=${modelId.length}, payloadLength=${payload.toString().length})"
}
