package dev.kestrel.providers.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A credential as data (design D15). `:core:privacy`'s CredentialStore keeps it encrypted; its toString prints only its
 * kind and lengths, never a key, a token, a client ID or a project.
 */
@Serializable
sealed interface Credential {
    @Serializable
    @SerialName("api-key")
    data class ApiKey(val key: String) : Credential {
        override fun toString(): String = "ApiKey(length=${key.length})"
    }

    /** An OAuth sign-in; [clientId] is the ChatGPT plan's issued `oaiapp_…` ID (design D9). */
    @Serializable
    @SerialName("oauth-token")
    data class OAuthToken(
        val access: String,
        val refresh: String?,
        val expiresAtMillis: Long?,
        val earliestRefreshAtMillis: Long? = null,
        val clientId: String? = null,
        val tokenEndpoint: String? = null,
        val region: String? = null,
    ) : Credential {
        override fun toString(): String =
            "OAuthToken(accessLength=${access.length}, refresh=${if (refresh == null) "none" else "set"}, " +
                "expiresAtMillis=$expiresAtMillis, earliestRefreshAtMillis=$earliestRefreshAtMillis, " +
                "clientId=${if (clientId == null) "none" else "set"}, tokenEndpoint=${if (tokenEndpoint == null) "none" else "set"}, region=$region)"
    }

    /**
     * Copilot's GitHub token, which is the bearer of every Copilot call and never expires on its own (design D12). A GHE.com
     * tenant's domain names the owner's organisation, so toString says only whether the domain is a tenant's.
     */
    @Serializable
    @SerialName("github-token")
    data class GitHubToken(val token: String, val domain: String) : Credential {
        override fun toString(): String = "GitHubToken(length=${token.length}, tenant=${domain != "github.com"})"
    }

    @Serializable
    @SerialName("aws-access-keys")
    data class AwsAccessKeys(val accessKeyId: String, val secretAccessKey: String, val sessionToken: String? = null) : Credential {
        override fun toString(): String =
            "AwsAccessKeys(accessKeyIdLength=${accessKeyId.length}, secretLength=${secretAccessKey.length}, " +
                "sessionToken=${if (sessionToken == null) "none" else "set"})"
    }

    /** The four fields that Kestrel keeps of a Google service-account key file (design D9). */
    @Serializable
    @SerialName("service-account-key")
    data class ServiceAccountKey(val clientEmail: String, val privateKeyPem: String, val privateKeyId: String?, val projectId: String) : Credential {
        override fun toString(): String =
            "ServiceAccountKey(clientEmailLength=${clientEmail.length}, privateKeyLength=${privateKeyPem.length}, " +
                "privateKeyId=${if (privateKeyId == null) "none" else "set"}, projectIdLength=${projectId.length})"
    }
}
