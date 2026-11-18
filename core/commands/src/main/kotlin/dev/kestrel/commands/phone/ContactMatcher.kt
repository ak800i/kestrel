package dev.kestrel.commands.phone

import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness

/**
 * Finds the contact and the number that "call X" names (design spec, section 13.6; research r20). It matches in Kestrel,
 * with the strict limits of section 9.6, so that Cyrillic and Latin spellings and names without diacritics find each
 * other: a name that the owner says exactly, then a first name alone as the first word of a contact's name, then the
 * matcher, whose single near miss is offered in "Which one?" as a confirmation. Equal matches are ranked with the starred
 * contacts first.
 */
object ContactMatcher {
    /** "Which one?" offers at most this many contacts; with more, Kestrel asks for the full name. */
    const val MAX_CHOICES = 4

    private val SAME_KINDS = mapOf("cell" to "mobile", "cellphone" to "mobile", "cell phone" to "mobile", "mobile phone" to "mobile")

    fun find(spoken: String, contacts: List<Contact>): ContactMatch {
        val said = Normalizer.normalize(spoken)
        if (said.isEmpty()) return ContactMatch.None
        val exact = contacts.filter { contact -> contact.names.any { Normalizer.normalize(it) == said } }
        if (exact.isNotEmpty()) return decide(ranked(exact))
        if (' ' !in said) {
            val first = contacts.filter { contact -> contact.names.any { Normalizer.words(it).firstOrNull() == said } }
            if (first.isNotEmpty()) return decide(ranked(first))
        }
        val candidates = contacts.mapIndexed { index, contact -> MatchCandidate(index.toString(), contact.names) }
        return when (val decision = LabelMatcher.decide(spoken, candidates, Strictness.STRICT)) {
            is MatchDecision.Found -> ContactMatch.One(contacts[decision.id.toInt()])
            // A single near miss is offered too, as a confirmation. LabelMatcher keeps at most MatchRules.MAX_AMBIGUOUS
            // close names, so more than Kestrel offers are counted here.
            is MatchDecision.Ambiguous ->
                if (decision.ids.size <= MAX_CHOICES) {
                    offer(decision.ids.map { contacts[it.toInt()] }.sortedByDescending { it.starred })
                } else {
                    ContactMatch.TooMany(maxOf(decision.ids.size, closeCount(spoken, contacts)))
                }
            MatchDecision.NotFound -> ContactMatch.None
        }
    }

    // How many of [contacts] LabelMatcher.decide finds as close to [spoken] as its best one, without its limit.
    private fun closeCount(spoken: String, contacts: List<Contact>): Int {
        val scores = contacts.mapNotNull { contact -> contact.names.maxOfOrNull { LabelMatcher.score(spoken, it, Strictness.STRICT) } }
        val best = scores.maxOrNull() ?: return 0
        val exact = scores.count { it == 1.0 }
        if (exact > 1) return exact
        return scores.count {
            it + MatchRules.TOLERANCE >= MatchRules.CLOSE && best - it <= MatchRules.NEAR + MatchRules.TOLERANCE
        }
    }

    /**
     * The number of [contact] to call: the one of the [kind] that the owner said ("mobile", "work", a custom label), else
     * the default number, else the only number. Numbers that differ only in their formatting count once, after the kind
     * has chosen among them, and the copy that is the default stays.
     */
    fun number(contact: Contact, kind: String?): NumberChoice {
        if (kind != null) {
            val wanted = kindOf(kind)
            return choice(distinct(contact.numbers.filter { kindOf(it.kind) == wanted }))
        }
        val entries = distinct(contact.numbers)
        entries.singleOrNull()?.let { return NumberChoice.One(it) }
        entries.firstOrNull { it.superPrimary }?.let { return NumberChoice.One(it) }
        return choice(entries)
    }

    // Each number once, in the order of its first copy; the copy that is the default when one is.
    private fun distinct(entries: List<PhoneEntry>): List<PhoneEntry> =
        entries.groupBy { entry -> entry.number.filter { it.isDigit() || it == '+' } }.values
            .map { copies -> copies.firstOrNull { it.superPrimary } ?: copies.first() }

    /** The kind of a number as the matcher compares it: folded, and "cell" as "mobile". */
    fun kindOf(kind: String): String = Normalizer.normalize(kind).let { SAME_KINDS[it] ?: it }

    private fun choice(entries: List<PhoneEntry>): NumberChoice = when (entries.size) {
        0 -> NumberChoice.None
        1 -> NumberChoice.One(entries[0])
        else -> NumberChoice.Several(entries)
    }

    private fun ranked(contacts: List<Contact>): List<Contact> =
        contacts.sortedWith(compareByDescending<Contact> { it.starred }.thenBy { Normalizer.normalize(it.names.firstOrNull().orEmpty()) })

    private fun decide(contacts: List<Contact>): ContactMatch = if (contacts.size == 1) ContactMatch.One(contacts[0]) else offer(contacts)

    private fun offer(contacts: List<Contact>): ContactMatch =
        if (contacts.size <= MAX_CHOICES) ContactMatch.Several(contacts) else ContactMatch.TooMany(contacts.size)
}
