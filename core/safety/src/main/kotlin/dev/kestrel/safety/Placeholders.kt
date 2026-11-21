package dev.kestrel.safety

/**
 * Kestrel's typed placeholders (design D17 of sub-project 5), such as `[OTP_1]`, which stand for values that the task's
 * vault keeps on the phone. A text that holds one is typed only after the owner's yes, and a confirmation names its kind,
 * never its value.
 */
object Placeholders {
    val PATTERN = Regex("\\[(OTP|CARD|IBAN|JMBG|TOKEN)_(\\d+)]")

    private val SPOKEN = mapOf(
        "OTP" to "your one-time code",
        "CARD" to "your card number",
        "IBAN" to "your IBAN",
        "JMBG" to "your JMBG number",
        "TOKEN" to "your access token",
    )

    fun holdsOne(text: String?): Boolean = text != null && PATTERN.containsMatchIn(text)

    /** [text] with each placeholder replaced by its kind's words for a confirmation: "your one-time code". */
    fun forConfirmation(text: String): String = PATTERN.replace(text) { SPOKEN.getValue(it.groupValues[1]) }
}
