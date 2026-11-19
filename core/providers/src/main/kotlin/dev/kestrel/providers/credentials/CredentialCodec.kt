package dev.kestrel.providers.credentials

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Turns a [Credential] into the bytes that `:app`'s StoredCredentials encrypts, and back (design D15). */
object CredentialCodec {
    private val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
    }

    fun encode(credential: Credential): ByteArray = json.encodeToString(Credential.serializer(), credential).encodeToByteArray()

    /** Throws IllegalArgumentException, without the bytes in its message, when [bytes] hold no credential. */
    fun decode(bytes: ByteArray): Credential =
        try {
            json.decodeFromString(Credential.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a stored credential")
        }
}
