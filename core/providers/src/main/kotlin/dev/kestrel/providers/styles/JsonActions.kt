package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.InvalidToolCall
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.ToolSpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON actions for models that handle tool calls badly (design D7). It wraps any adapter: it sends no tool definitions,
 * adds a fixed instruction with each tool's name, description and parameter schema to the end of the system prompt, and
 * sets [ChatRequest.jsonSchema] to the action schema, which each adapter passes on where its style allows. The reply's
 * first JSON object, after any code fence, must name a tool of the request and give an `arguments` object with the
 * schema's required keys; it becomes a normal tool call with the ID `json-<n>`. Any other reply keeps its text and gets
 * an [InvalidToolCall], so that the agent can ask once more (spec 14.2). Earlier calls and results go back as text.
 */
class JsonActions(private val inner: ChatAdapter) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val names = request.tools.map { it.name }
        val sent = request.copy(
            system = listOf(request.system, instruction(request.tools)).filter { it.isNotEmpty() }.joinToString("\n\n"),
            tools = emptyList(),
            toolChoice = ToolChoice.AUTO,
            jsonSchema = schema(names),
        )
        val response = inner.complete(sent, target)
        val text = response.message.parts.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }
        val action = read(text, request.tools)
        if (action is Action.Bad) {
            return response.copy(invalidToolCall = InvalidToolCall(action.kind, text))
        }
        action as Action.Good
        val id = "json-${request.messages.sumOf { message -> (message as? ChatMessage.Assistant)?.toolCalls?.count { it.id.startsWith("json-") } ?: 0 } + 1}"
        val parts = response.message.parts.filterNot { it is AssistantPart.Text || it is AssistantPart.ToolCall } +
            AssistantPart.ToolCall(id, action.tool, action.arguments)
        return response.copy(message = ChatMessage.Assistant(parts), stop = StopReason.TOOL_CALL, invalidToolCall = null)
    }

    private sealed interface Action {
        class Good(val tool: String, val arguments: JsonObject) : Action

        class Bad(val kind: InvalidKind) : Action
    }

    private fun read(text: String, tools: List<ToolSpec>): Action {
        val unfenced = FENCE.find(text)?.groupValues?.get(1) ?: text
        val start = unfenced.indexOf('{')
        if (start < 0) return Action.Bad(InvalidKind.NO_ACTION)
        val end = objectEnd(unfenced, start) ?: return Action.Bad(InvalidKind.BAD_JSON)
        val action = try {
            Wire.json.parseToJsonElement(unfenced.substring(start, end + 1)) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return Action.Bad(InvalidKind.BAD_JSON)
        val name = (action["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Action.Bad(InvalidKind.BAD_JSON)
        val tool = tools.firstOrNull { it.name == name } ?: return Action.Bad(InvalidKind.UNKNOWN_TOOL)
        val arguments = action["arguments"] as? JsonObject ?: return Action.Bad(InvalidKind.BAD_JSON)
        val required = (tool.parameters["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (required.any { it !in arguments }) return Action.Bad(InvalidKind.MISSING_ARGUMENT)
        return Action.Good(name, arguments)
    }

    /** The index of the brace that closes the object opened at [start], with strings and escapes skipped. */
    private fun objectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    companion object {
        private val FENCE = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")

        /** The fixed instruction of design D7. */
        fun instruction(tools: List<ToolSpec>): String = buildString {
            append("You act by choosing one of these actions. Reply with exactly one JSON object of the form ")
            append("{\"tool\": \"<name>\", \"arguments\": {...}} and nothing else.\n")
            append("Actions:")
            for (tool in tools) append("\n- ${tool.name}: ${tool.description} Parameters: ${tool.parameters}")
        }

        /** The action schema of design D7: `tool` is one of the names, `arguments` any object. */
        fun schema(names: List<String>): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tool") {
                    put("type", "string")
                    putJsonArray("enum") { names.forEach { add(it) } }
                }
                putJsonObject("arguments") { put("type", "object") }
            }
            putJsonArray("required") {
                add("tool")
                add("arguments")
            }
        }
    }
}
