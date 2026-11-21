package dev.kestrel.safety

/** The kinds of values that redaction replaces, with the words that Kestrel speaks for each (design D17 and D24). */
enum class SecretKind(val tag: String, val spoken: String) {
    OTP("OTP", "a one-time code"),
    CARD("CARD", "a card number"),
    IBAN("IBAN", "an IBAN"),
    JMBG("JMBG", "a JMBG number"),
    TOKEN("TOKEN", "an access token"),
}

/**
 * The task's vault (design D17 of sub-project 5; spec 12.6): a map from Kestrel's placeholders, numbered per kind and
 * task, to the real values, in memory only, owned by one task and cleared in its `finally`. The same value always gets
 * the same placeholder, so a conversation's stable part never changes. A placeholder is revealed only in `type_text`'s
 * text after the owner's yes, without the separators that the screen showed. Its text form prints only how many values
 * it holds.
 */
class Vault {
    private val held = LinkedHashMap<String, Pair<SecretKind, String>>()
    private val placeholders = HashMap<Pair<SecretKind, String>, String>()
    private val counts = HashMap<SecretKind, Int>()

    /** The placeholder of [value], a new one the first time, such as `[OTP_1]`. */
    fun placeholderFor(kind: SecretKind, value: String): String {
        val key = kind to canonical(kind, value)
        placeholders[key]?.let { return it }
        val number = (counts[kind] ?: 0) + 1
        counts[kind] = number
        val placeholder = "[${kind.tag}_$number]"
        placeholders[key] = placeholder
        // Kept in the form that a field takes, so that `type_text` types "482913", not "482 913" (review 5A-4 Minor 3).
        held[placeholder] = key
        return placeholder
    }

    fun valueOf(placeholder: String): String? = held[placeholder]?.second

    fun kindOf(placeholder: String): SecretKind? = held[placeholder]?.first

    /** The vault's placeholders that [text] holds, in their order; a placeholder that the vault does not hold is left out. */
    fun placeholdersIn(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.filter { it in held }.toList()

    /** [text] with each of the vault's placeholders replaced by its value, for `type_text` after the owner's yes. */
    fun reveal(text: String): String = PLACEHOLDER.replace(text) { held[it.value]?.second ?: it.value }

    /** [text] with each of the vault's placeholders replaced by its kind's words, for what Kestrel speaks (design D24). */
    fun spoken(text: String): String = PLACEHOLDER.replace(text) { held[it.value]?.first?.spoken ?: it.value }

    /** The real values, in the form that [reveal] gives. */
    val values: Collection<String> get() = held.values.map { it.second }

    val isEmpty: Boolean get() = held.isEmpty()

    /** The known values with their kinds, the longest first, for redaction's search for a value that it met before. */
    internal fun known(): List<Pair<SecretKind, String>> = held.values.sortedByDescending { it.second.length }

    fun clear() {
        held.clear()
        placeholders.clear()
        counts.clear()
    }

    override fun toString(): String = "Vault(values=${held.size})"

    companion object {
        /** Kestrel's placeholders; the same pattern as [Placeholders.PATTERN]. */
        val PLACEHOLDER: Regex = Placeholders.PATTERN

        /** The form in which two values count as the same and are revealed: digits only, or an IBAN without spaces in capitals. */
        internal fun canonical(kind: SecretKind, value: String): String = when (kind) {
            SecretKind.OTP, SecretKind.CARD, SecretKind.JMBG -> value.filter(Char::isDigit)
            SecretKind.IBAN -> value.filter(Char::isLetterOrDigit).uppercase()
            SecretKind.TOKEN -> value
        }
    }
}
