package dev.kestrel.providers.credentials

/** Where credentials are kept: `:app` implements it over `:core:privacy`'s CredentialStore (design D1 and D15). */
interface CredentialVault {
    /** Null when there is no credential with [id] or it could not be read. */
    suspend fun read(id: String): Credential?

    /** Stores [credential] under [id], replacing an earlier one. */
    suspend fun write(id: String, credential: Credential)

    suspend fun delete(id: String)
}
