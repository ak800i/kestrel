package dev.kestrel.conversation.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/** A conversation's random 128-bit id (design 9.1 of sub-project 7): 32 lowercase hexadecimal characters. */
@Serializable
@JvmInline
value class ConversationId(val value: String) {
    init {
        require(HEX.matches(value)) { "A conversation id is 32 hexadecimal characters" }
    }

    companion object {
        private val HEX = Regex("[0-9a-f]{32}")

        /** A new random id. */
        fun random(random: SecureRandom = SecureRandom()): ConversationId {
            val bytes = ByteArray(16).also(random::nextBytes)
            return ConversationId(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}
