package dev.kestrel.providers.chat

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl

/**
 * Everything an adapter needs to send a request to one model (design D4 and "Shared names"): [baseUrl] holds the style's
 * base, to which the adapter appends its path; [sentModelId] is what goes into the request or the URL (a model ID, an
 * Azure deployment name, or a Bedrock model or inference-profile ID); [headers] holds every header that the adapter does
 * not add itself. The base URL can hold a Vertex project or an Azure resource, and the model ID a Bedrock ARN, so toString
 * prints neither.
 */
data class ModelTarget(
    val providerId: String,
    val style: RequestStyle,
    val baseUrl: HttpUrl,
    val model: ModelEntry,
    val sentModelId: String = model.id,
    val auth: RequestAuth,
    val headers: RequestHeaders = RequestHeaders.NONE,
    val options: StyleOptions = StyleOptions(),
) {
    override fun toString(): String =
        "ModelTarget(provider=$providerId, style=$style, modelIdLength=${sentModelId.length}, options=$options)"
}

/**
 * Per-target switches of the adapters (design D4, D5, D12 and D17): [chatgptPlan] makes the Responses adapter follow the
 * ChatGPT plan's preview limits and map its error codes; [stripThinking], [sanitizeToolCallIds] and [messagesPath] act in
 * the Anthropic adapter (Copilot sets `"/messages"`); [wire] chooses the wire format of a VERTEX or AZURE model (null: the
 * adapter's own rule); [compat] replaces the model's compat flags for Chat Completions (Copilot's); [stream] overrides the
 * streaming rule of design D4 (null: the rule).
 */
data class StyleOptions(
    val chatgptPlan: Boolean = false,
    val stripThinking: Boolean = false,
    val sanitizeToolCallIds: Boolean = false,
    val messagesPath: String = "/v1/messages",
    val wire: RequestStyle? = null,
    val compat: CompatFlags? = null,
    val stream: Boolean? = null,
)

/** The headers of a target that depend on the request, such as Copilot's `x-initiator` (design D12). */
fun interface RequestHeaders {
    fun headers(request: ChatRequest): Map<String, String>

    companion object {
        val NONE: RequestHeaders = RequestHeaders { emptyMap() }
    }
}
