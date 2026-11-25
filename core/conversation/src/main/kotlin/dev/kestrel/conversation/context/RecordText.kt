package dev.kestrel.conversation.context

import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault

/**
 * The owner's words in their record's form (design 7.1, 8.1 and 8.2 of sub-project 7): redacted once, here, with a vault
 * of their own, and every value that the redactor found named by its kind's words ("a one-time code"), so that no record
 * holds a value or a placeholder that a later run could type back. Kestrel's own texts and the run's traces arrive in this
 * form already and never pass here. `RecordLines` quotes and escapes every text of a record when it writes its lines.
 */
object RecordText {
    fun of(text: String): String {
        val vault = Vault()
        return vault.spoken(Redactor(vault).redact(text))
    }
}
