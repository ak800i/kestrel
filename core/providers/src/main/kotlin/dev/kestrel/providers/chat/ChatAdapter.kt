package dev.kestrel.providers.chat

/** Turns a [ChatRequest] into one request style and its answer into one complete [ChatResponse] (spec 10.3). */
interface ChatAdapter {
    suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse
}
