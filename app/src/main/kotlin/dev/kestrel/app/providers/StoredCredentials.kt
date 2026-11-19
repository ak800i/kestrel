package dev.kestrel.app.providers

import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialCodec
import dev.kestrel.providers.credentials.CredentialVault

/**
 * The providers' credentials in `:core:privacy`'s encrypted store (design spec, section 10.7): `:core:providers` gives a
 * credential as data, and the store keeps its bytes under the credentials key. A credential that cannot be read is null,
 * so that its profile asks the owner to sign in again.
 */
internal class StoredCredentials(private val store: CredentialStore) : CredentialVault {
    override suspend fun read(id: String): Credential? {
        val bytes = store.get(id) ?: return null
        return try {
            CredentialCodec.decode(bytes)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun write(id: String, credential: Credential) {
        store.put(id, CredentialCodec.encode(credential))
    }

    override suspend fun delete(id: String) {
        store.delete(id)
    }
}
