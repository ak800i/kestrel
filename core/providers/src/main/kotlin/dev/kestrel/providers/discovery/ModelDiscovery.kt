package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import kotlinx.serialization.Serializable

/**
 * A model that a provider's own list reports (design D13). A local server's model names may be the owner's own, so
 * toString prints only lengths and kinds.
 */
@Serializable
data class DiscoveredModel(
    val id: String,
    val name: String,
    val input: Set<InputKind> = setOf(InputKind.TEXT),
    val reasoning: Boolean = false,
    val reasoningEfforts: List<String>? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val tools: Boolean? = null,
    val streaming: Boolean? = null,
    val style: RequestStyle? = null,
) {
    override fun toString(): String =
        "DiscoveredModel(idLength=${id.length}, input=$input, reasoning=$reasoning, tools=$tools, style=$style)"
}

/** Reads a provider's model list; it runs only when a profile is created or the owner taps "Refresh models". */
fun interface ModelDiscovery {
    suspend fun list(): List<DiscoveredModel>
}
