package dev.kestrel.agent.loop

import dev.kestrel.safety.Redactor

/**
 * A text that may go into a request (design D3 and D17): it was redacted once, when it entered the conversation, or it is
 * Kestrel's own text built from fixed sentences and parts that were redacted before. The type keeps a text from being
 * redacted twice, which would turn Kestrel's own `[OTP_1]` into `(OTP_1)`. toString prints only the length (D25).
 */
class Redacted private constructor(val text: String) {
    override fun equals(other: Any?): Boolean = other is Redacted && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "Redacted(length=${text.length})"

    companion object {
        /** [text] redacted with [neighbours], the texts beside it on the screen or in the same data line (D17). */
        fun of(redactor: Redactor, text: String, neighbours: List<String> = emptyList()): Redacted =
            Redacted(redactor.redact(text, neighbours))

        /**
         * Kestrel's own text: a fixed sentence, or one built only from parts that are redacted already (an observation, a
         * `shownLabel`, a result line). It is never passed to the redactor.
         */
        fun kestrel(text: String): Redacted = Redacted(text)

        /**
         * A text in its record's form (design 7.1 and 8.1 of sub-project 7): redacted once, when it entered its own run,
         * with each vault value as its kind's words. It is never passed to the redactor again.
         */
        fun recorded(text: String): Redacted = Redacted(text)
    }
}
