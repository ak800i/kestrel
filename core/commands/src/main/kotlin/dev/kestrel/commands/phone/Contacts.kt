package dev.kestrel.commands.phone

/**
 * One phone number of a contact: the [number] as the contacts store it, its [kind] ("mobile", "work", "home", "main",
 * "other" or the owner's own label) and whether it is the contact's default number. Its text form never shows the number.
 */
data class PhoneEntry(val number: String, val kind: String, val superPrimary: Boolean) {
    override fun toString() = "PhoneEntry(${number.length} characters, kind of ${kind.length} characters, superPrimary=$superPrimary)"
}

/**
 * A contact as "call X" sees it: its [names] (the display name first, then nicknames), its [numbers] and whether the owner
 * starred it. Its text form shows only counts.
 */
data class Contact(val id: Long, val names: List<String>, val numbers: List<PhoneEntry>, val starred: Boolean) {
    override fun toString() = "Contact(${names.size} names, ${numbers.size} numbers, starred=$starred)"
}

/** Whom the owner's words name among the contacts. */
sealed interface ContactMatch {
    data class One(val contact: Contact) : ContactMatch

    /**
     * Up to [ContactMatcher.MAX_CHOICES] contacts for "Which one?"; a single near miss is offered as "Which one? 1, X.",
     * whose "yes" ends the command and is read again as a new utterance.
     */
    data class Several(val contacts: List<Contact>) : ContactMatch

    /** More contacts than "Which one?" can offer. */
    data class TooMany(val count: Int) : ContactMatch

    data object None : ContactMatch
}

/** Which of a contact's numbers to call. */
sealed interface NumberChoice {
    data class One(val entry: PhoneEntry) : NumberChoice

    /** Several numbers, for "Which number?". */
    data class Several(val entries: List<PhoneEntry>) : NumberChoice

    data object None : NumberChoice
}
