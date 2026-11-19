package dev.kestrel.providers.signin

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.ChoiceOverride
import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.credentials.ApiKeys
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.Hosts
import dev.kestrel.providers.styles.VertexUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the add flow checks a credential before it saves the profile (design D9). */
enum class CheckMethod {
    /** A model discovery call, where the provider has a list. */
    DISCOVERY,

    /** Vertex's token exchange with the service-account key. */
    TOKEN_EXCHANGE,

    /** One short request ("Reply with OK."), for which the owner chooses or types the model first. */
    SHORT_REQUEST,
}

/**
 * The entries of the add flow that need no sign-in flow (design D9 and D11): a pasted API key, a custom base URL, a local
 * server's address, AWS access keys and a Google service-account key file; and how the flow checks each.
 */
object Entries {
    private val json = Json { isLenient = true }

    /**
     * The pasted key, trimmed, when OkHttp can send it as a header: not empty, and only printable ASCII without spaces.
     * Otherwise null, and the page says "The key has a character that keys do not have. Paste it again."
     */
    fun apiKey(pasted: String): Credential.ApiKey? = pasted.trim().takeIf(ApiKeys::acceptable)?.let { Credential.ApiKey(it) }

    /** A custom base URL of an API-key choice: HTTPS, without user information, a query or a fragment. */
    fun customBaseUrl(text: String): HttpUrl? =
        text.trim().toHttpUrlOrNull()?.takeIf { it.isHttps && plain(it) }

    /**
     * A local server's address (design D11): plain HTTP only to a local host (an IP address of the phone or the owner's
     * network, `localhost` or a `.local` name), HTTPS to any host; without user information, a query or a fragment.
     */
    fun serverAddress(text: String): HttpUrl? =
        text.trim().toHttpUrlOrNull()?.takeIf { plain(it) && (it.isHttps || Hosts.isLocal(it.host)) }

    /** Whether the page warns that the connection to [url] is not encrypted (spec 13.1). */
    fun unencrypted(url: HttpUrl): Boolean = !url.isHttps

    /** AWS access keys for Bedrock, each part sendable in a signed request. */
    fun awsKeys(accessKeyId: String, secretAccessKey: String, sessionToken: String?): Credential.AwsAccessKeys? {
        val id = accessKeyId.trim().takeIf(ApiKeys::acceptable) ?: return null
        val secret = secretAccessKey.trim().takeIf(ApiKeys::acceptable) ?: return null
        val session = sessionToken?.trim()?.takeIf { it.isNotEmpty() }
        if (session != null && !ApiKeys.acceptable(session)) return null
        return Credential.AwsAccessKeys(id, secret, session)
    }

    /**
     * The four fields that Kestrel keeps of a Google service-account key file (`client_email`, `private_key`,
     * `private_key_id` and `project_id`, as Google's page "Create and delete service account keys" shows the file), or
     * null when [text] is not such a file. Kestrel forgets the file itself.
     */
    fun serviceAccount(text: String): Credential.ServiceAccountKey? {
        val file = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (file.string("type") != "service_account") return null
        val email = file.string("client_email")?.takeIf { it.isNotBlank() } ?: return null
        val key = file.string("private_key")?.takeIf { "-----BEGIN PRIVATE KEY-----" in it } ?: return null
        // A project ID that is not in Google's form would fail every request (plan 4B's VertexUrls), so the import refuses it.
        val project = file.string("project_id")?.takeIf(VertexUrls::validProject) ?: return null
        return Credential.ServiceAccountKey(email, key, file.string("private_key_id")?.takeIf { it.isNotBlank() }, project)
    }

    /**
     * How the add flow checks a profile of [provider] with [choice] (design D9): Vertex by its token exchange; Azure, and a
     * provider whose catalog says STATIC, by one short request, for which the owner chooses or types the model first;
     * everything else, among them the local servers and the sign-ins with their own lists, by model discovery.
     */
    fun checkMethod(provider: ProviderEntry, choice: ChoiceOverride?): CheckMethod = when {
        provider.style == RequestStyle.VERTEX -> CheckMethod.TOKEN_EXCHANGE
        provider.style == RequestStyle.AZURE -> CheckMethod.SHORT_REQUEST
        choice?.method == AuthMethod.SERVER_ADDRESS -> CheckMethod.DISCOVERY
        SignInRegistry.kind(provider.id, choice?.choiceId.orEmpty()) != null -> CheckMethod.DISCOVERY
        provider.discovery == Discovery.STATIC -> CheckMethod.SHORT_REQUEST
        else -> CheckMethod.DISCOVERY
    }

    /** The short request of a check (design D9): "Reply with OK." with at most 16 output tokens. */
    fun checkRequest(): ChatRequest =
        ChatRequest(system = "", messages = listOf(ChatMessage.User(listOf(Part.Text("Reply with OK.")))), maxOutputTokens = 16)

    /** A key as the pages show it after entry: its last four characters (design D15). */
    fun masked(key: String): String = "…" + key.takeLast(4)

    private fun plain(url: HttpUrl): Boolean =
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null
}
