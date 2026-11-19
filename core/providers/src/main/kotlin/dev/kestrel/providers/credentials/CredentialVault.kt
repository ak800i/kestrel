package dev.kestrel.providers.credentials

/** Where credentials are kept: `:app` implements it over `:core:privacy`'s CredentialStore (design D1 and D15). */
interface CredentialVault {
    /**
     * The credential stored under [id]. Null when there is none, when it is not authentic (changed, or moved from another
     * ID) or when it cannot be decoded; its profile then asks the owner to sign in again. A failure of Android's Keystore
     * throws instead, because it can be temporary and the credential may be readable later.
     */
    suspend fun read(id: String): Credential?

    /** Stores [credential] under [id], replacing an earlier one. */
    suspend fun write(id: String, credential: Credential)

    suspend fun delete(id: String)
}
