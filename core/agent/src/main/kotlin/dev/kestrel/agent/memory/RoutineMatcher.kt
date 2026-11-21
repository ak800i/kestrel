package dev.kestrel.agent.memory

import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness

/** A routine whose phrasing matched the owner's request, with the value of each slot (design D23). */
data class RoutineMatch(val routine: Routine, val values: Map<String, String>) {
    override fun toString(): String = "RoutineMatch(routine=$routine, values=${values.size})"
}

/**
 * Matches the owner's request against every routine's phrasings on the phone, without a model (design D23; spec 11.1 and
 * 16.2): after `Normalizer`, the fixed words must match in order, each by `LabelMatcher` in strict mode, and the slots take
 * the words between them. A `contact` slot must name one contact, through an alias of memory or one of the contact names
 * that `:app` read; a `place` slot is an alias or an address; a `time` slot must read as a time; a `text` slot takes the
 * words as the owner said them. A routine runs only when all its slots are filled and no other routine matches.
 */
object RoutineMatcher {
    private val TIME = Regex("""^(\d{1,2})([:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?$""", RegexOption.IGNORE_CASE)
    private val MARKER = Regex("""\{([a-z][a-z0-9_]*)}""")

    fun match(request: String, routines: List<Routine>, aliases: Set<String>, contactNames: Set<String>): RoutineMatch? {
        val matches = routines.mapNotNull { routine ->
            routine.phrasings.firstNotNullOfOrNull { phrasing -> matchPhrasing(request, phrasing, routine, aliases, contactNames) }
                ?.let { RoutineMatch(routine, it) }
        }
        return matches.singleOrNull()
    }

    /** Whether [phrasing] matches [request] at all, slots aside; the maker refuses a phrasing that matches another. */
    fun overlaps(request: String, phrasing: String): Boolean {
        val tokens = tokens(phrasing)
        val folded = Normalizer.withOffsets(request)
        val words = folded.words.map { folded.text.substring(it) }
        return bind(tokens, 0, words, 0, mutableMapOf()) != null
    }

    private fun matchPhrasing(request: String, phrasing: String, routine: Routine, aliases: Set<String>, contactNames: Set<String>): Map<String, String>? {
        val tokens = tokens(phrasing)
        val slotNames = tokens.filterIsInstance<Token.SlotRef>().map { it.name }.toSet()
        if (slotNames != routine.slots.map { it.name }.toSet()) return null
        val folded = Normalizer.withOffsets(request)
        val words = folded.words.map { folded.text.substring(it) }
        val spans = bind(tokens, 0, words, 0, mutableMapOf()) ?: return null
        val values = spans.mapValues { (_, span) -> request.substring(folded.span(span.first, span.last + 1)).trim() }
        for (slot in routine.slots) {
            val value = values[slot.name] ?: return null
            val ok = when (slot.type) {
                SlotType.CONTACT -> aliasMatches(value, aliases) || oneContact(value, contactNames)
                SlotType.PLACE -> value.isNotBlank()
                SlotType.TIME -> TIME.matches(value.trim())
                SlotType.TEXT -> value.isNotBlank()
            }
            if (!ok) return null
        }
        return values
    }

    private sealed interface Token {
        data class Word(val word: String) : Token

        data class SlotRef(val name: String) : Token
    }

    private fun tokens(phrasing: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var at = 0
        for (marker in MARKER.findAll(phrasing)) {
            Normalizer.words(phrasing.substring(at, marker.range.first)).forEach { tokens += Token.Word(it) }
            tokens += Token.SlotRef(marker.groupValues[1])
            at = marker.range.last + 1
        }
        Normalizer.words(phrasing.substring(at)).forEach { tokens += Token.Word(it) }
        return tokens
    }

    // Binds the tokens from [t] to the words from [w]; each slot takes one or more words. Returns each slot's word range.
    private fun bind(tokens: List<Token>, t: Int, words: List<String>, w: Int, bound: MutableMap<String, IntRange>): Map<String, IntRange>? {
        if (t == tokens.size) return if (w == words.size) bound.toMap() else null
        return when (val token = tokens[t]) {
            is Token.Word -> if (w < words.size && same(words[w], token.word)) bind(tokens, t + 1, words, w + 1, bound) else null
            is Token.SlotRef -> {
                for (end in w until words.size) {
                    bound[token.name] = w..end
                    bind(tokens, t + 1, words, end + 1, bound)?.let { return it }
                }
                bound.remove(token.name)
                null
            }
        }
    }

    private fun same(spoken: String, fixed: String): Boolean =
        spoken == fixed || LabelMatcher.decide(spoken, listOf(MatchCandidate("w", listOf(fixed))), Strictness.STRICT) is MatchDecision.Found

    private fun aliasMatches(value: String, aliases: Set<String>): Boolean =
        aliases.any { LabelMatcher.decide(value, listOf(MatchCandidate("a", listOf(it))), Strictness.STRICT) is MatchDecision.Found }

    private fun oneContact(value: String, names: Set<String>): Boolean {
        val contacts = names.mapIndexed { index, name -> Contact(index.toLong(), listOf(name), emptyList(), starred = false) }
        return ContactMatcher.find(value, contacts) is ContactMatch.One
    }
}
