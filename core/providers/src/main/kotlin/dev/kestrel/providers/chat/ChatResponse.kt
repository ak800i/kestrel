package dev.kestrel.providers.chat

/** One complete answer of a model (design D3), whichever transport the adapter used (spec 10.3). */
data class ChatResponse(
    val message: ChatMessage.Assistant,
    val stop: StopReason,
    val usage: Usage,
    /** The model that the provider reports, which may be a Bedrock ARN with an account ID, so only its length is printed. */
    val modelId: String,
    /** Set when the model asked for a tool in a form that Kestrel cannot use (design D3 and D7). */
    val invalidToolCall: InvalidToolCall? = null,
) {
    override fun toString(): String =
        "ChatResponse(stop=$stop, message=$message, usage=$usage, modelIdLength=${modelId.length}, invalidToolCall=$invalidToolCall)"
}

enum class StopReason { END, TOOL_CALL, MAX_TOKENS, FILTERED, OTHER }

/** A tool call that Kestrel cannot use. Its text is the model's reply, so only its length is printed. */
data class InvalidToolCall(val kind: InvalidKind, val text: String) {
    override fun toString(): String = "InvalidToolCall(kind=$kind, textLength=${text.length})"
}

enum class InvalidKind { ARGUMENTS_NOT_OBJECT, UNKNOWN_TOOL, MISSING_ARGUMENT, NO_ACTION, BAD_JSON }
