package dev.kestrel.providers.chat

/** Checks a request before any adapter runs (design D3). */
object ChatRequests {
    /**
     * Throws IllegalArgumentException when a tool call of an assistant message has no result with its ID before the next
     * user or assistant message, when two calls of one message share an ID, when a tool result's ID belongs to no earlier
     * call (design D32 item 8 of sub-project 5), when `cacheAfter` names no message, or when maxOutputTokens is not
     * positive. Every style refuses a call without a result and a result without its call (OpenAI, Anthropic, Bedrock;
     * Gemini wants all calls of a step before their results).
     */
    fun check(request: ChatRequest) {
        require(request.maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        val messages = request.messages
        request.cacheAfter?.let { require(it in messages.indices) { "cacheAfter names no message" } }
        val called = HashSet<String>()
        for ((index, message) in messages.withIndex()) {
            if (message is ChatMessage.ToolResult) {
                require(message.callId in called) { "Message $index is a tool result without its call" }
                continue
            }
            if (message !is ChatMessage.Assistant) continue
            val calls = message.toolCalls
            if (calls.isEmpty()) continue
            val ids = calls.map { it.id }
            require(ids.toSet().size == ids.size) { "Message $index has two tool calls with one ID" }
            called += ids
            val results = messages.drop(index + 1)
                .takeWhile { it is ChatMessage.ToolResult }
                .map { (it as ChatMessage.ToolResult).callId }
                .toSet()
            val missing = ids.count { it !in results }
            require(missing == 0) { "Message $index has $missing tool calls without a result" }
        }
    }
}
