package dev.kestrel.providers.chat

/** Checks a request before any adapter runs (design D3). */
object ChatRequests {
    /**
     * Throws IllegalArgumentException when a tool call of an assistant message has no result with its ID before the next
     * user or assistant message, when two calls of one message share an ID, or when maxOutputTokens is not positive. Every
     * style refuses a call without a result (OpenAI, Anthropic, Bedrock; Gemini wants all calls of a step before their
     * results).
     */
    fun check(request: ChatRequest) {
        require(request.maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        val messages = request.messages
        for ((index, message) in messages.withIndex()) {
            if (message !is ChatMessage.Assistant) continue
            val calls = message.toolCalls
            if (calls.isEmpty()) continue
            val ids = calls.map { it.id }
            require(ids.toSet().size == ids.size) { "Message $index has two tool calls with one ID" }
            val results = messages.drop(index + 1)
                .takeWhile { it is ChatMessage.ToolResult }
                .map { (it as ChatMessage.ToolResult).callId }
                .toSet()
            val missing = ids.count { it !in results }
            require(missing == 0) { "Message $index has $missing tool calls without a result" }
        }
    }
}
