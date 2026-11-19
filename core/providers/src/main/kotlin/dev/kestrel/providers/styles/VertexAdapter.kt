package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp

/**
 * Google Vertex AI (spec 10.3, design D4 and D16, research r35): one provider for Gemini and Claude. A model's wire is
 * [dev.kestrel.providers.chat.StyleOptions.wire], else its catalog row's style, else Claude for an ID that names a Claude
 * model and Gemini otherwise. Gemini goes through the Gemini adapter at `publishers/google/models/<id>:streamGenerateContent`;
 * Claude through the Anthropic adapter's Vertex form at `publishers/anthropic/models/<id>:rawPredict`. [ModelTarget.baseUrl]
 * is `VertexUrls.base(location, project)`, the token is the service account's (ServiceAccountAuth), and the network log
 * records `{project}` instead of the project. A [dev.kestrel.providers.chat.StyleOptions.wire] other than GEMINI or
 * ANTHROPIC fails with NotAllowed without a request.
 */
internal class VertexAdapter(http: ProviderHttp) : ChatAdapter {
    private val gemini = GeminiAdapter(http)
    private val anthropic = AnthropicAdapter(http)

    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val wire = target.options.wire
            ?: target.model.style?.takeIf { it == RequestStyle.GEMINI || it == RequestStyle.ANTHROPIC }
            ?: if (AnthropicModels.identity(target.sentModelId) != null) RequestStyle.ANTHROPIC else RequestStyle.GEMINI
        // As AzureAdapter does: a wire that Vertex does not serve fails before a request.
        if (wire != RequestStyle.GEMINI && wire != RequestStyle.ANTHROPIC) throw ProviderException(ProviderError.NotAllowed)
        return if (wire == RequestStyle.ANTHROPIC) {
            val url = target.baseUrl.newBuilder()
                .addPathSegments("publishers/anthropic/models")
                .addPathSegment("${target.sentModelId}:rawPredict")
                .build()
            anthropic.complete(request, target, url, vertex = true, logPath = VertexUrls.logPath(url))
        } else {
            val stream = target.options.stream != false
            val url = GeminiAdapter.url(target.baseUrl, "publishers/google/models", target.sentModelId, stream)
            gemini.complete(request, target, url, stream, logPath = VertexUrls.logPath(url))
        }
    }
}
