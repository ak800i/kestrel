package dev.kestrel.conversation.context

import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault

/**
 * A text in its record's form (design 7.1, 8.1 and 8.2 of sub-project 7): redacted once, here, with a vault of its own,
 * and every value that the redactor found named by its kind's words ("a one-time code"), so that no record holds a value
 * or a placeholder that a later run could type back. Kestrel's own texts and the run's traces arrive in this form already
 * and never pass here; the owner's words and an app's launcher label do. `RecordLines` quotes and escapes every text of a
 * record when it writes the record's lines.
 */
object RecordText {
    /** The owner's words. */
    fun of(text: String): String {
        val vault = Vault()
        return vault.spoken(Redactor(vault).redact(text))
    }

    /** An app's launcher label, which the app chose: redacted, then cut as a label on the screen is (`ScreenText.LABEL_LIMIT`). */
    fun label(text: String): String = ScreenText.cut(of(text), ScreenText.LABEL_LIMIT)
}
