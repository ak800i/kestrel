package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import java.net.IDN
import java.util.Locale

/**
 * The owner's own words in a task (D8 and D13 of sub-project 5; D8 of sub-project 7; spec 12.3): his command, the
 * conversation's earlier commands, his answers and his steering. Each text is kept as its folded words, in their order,
 * as the tokens that can name a host, and as its digits. Only his own texts are ever added, never one of the screen, a
 * data tool or the model, so what it holds is his, and every rule of the safety check that asks for his words reads it:
 * typing his words, a contact's number that he said, a search of his words and a link that he named. toString prints
 * only the count.
 */
class OwnerText private constructor(private val texts: List<Text>) {
    // One of his texts: its folded words, its host tokens and its digits.
    private class Text(val words: List<String>, val hostTokens: List<String>, val digits: String) {
        override fun equals(other: Any?): Boolean = other is Text && other.words == words && other.hostTokens == hostTokens && other.digits == digits

        override fun hashCode(): Int = 31 * (31 * words.hashCode() + hostTokens.hashCode()) + digits.hashCode()
    }

    /** Every folded word of his texts. */
    val words: Set<String> = texts.flatMapTo(mutableSetOf()) { it.words }

    /** The digits of each of his texts that holds any, in their order. */
    val digits: List<String> = texts.map { it.digits }.filter { it.isNotEmpty() }

    /** These words with his [text]. */
    operator fun plus(text: String): OwnerText = OwnerText(texts + Text(Normalizer.words(text), hostTokens(text), TaskSafety.digitsOf(text)))

    /**
     * Whether he named [phrase], a whole link other than an https one, in one of his texts: its folded words in a row
     * there, or all of them run together as one of its words. A phrase never spans two of his texts.
     */
    fun names(phrase: String): Boolean {
        val words = Normalizer.words(phrase)
        if (words.isEmpty()) return false
        val joined = words.joinToString("")
        return texts.any { text -> text.words.windowed(words.size).any { it == words } || joined in text.words }
    }

    /**
     * Whether he named [host] in one of his texts: its labels, lowercased, in their ASCII form and without a trailing dot,
     * are a run of that text's host tokens. Hyphens stay, so that "kestrel.example.com" never names the lookalike
     * `kestrel-example.com`; and the whole host is needed, so that "wikipedia.org" names no subdomain of it.
     */
    fun namesHost(host: String): Boolean {
        val labels = ascii(host.trimEnd('.'))?.split('.') ?: return false
        if (labels.any { it.isEmpty() }) return false
        return texts.any { text -> text.hostTokens.windowed(labels.size).any { it == labels } }
    }

    override fun equals(other: Any?): Boolean = other is OwnerText && other.texts == texts

    override fun hashCode(): Int = texts.hashCode()

    override fun toString(): String = "OwnerText(texts=${texts.size})"

    companion object {
        // A spoken dot between a host's labels, a token that can be no label, and what lies between a text's host tokens.
        private const val SPOKEN_DOT = "dot"
        private const val NO_LABEL = ""
        private val SEPARATORS = Regex("""[^\p{L}\p{N}\p{M}-]+""")

        val NONE = OwnerText(emptyList())

        /** The owner's [texts], in their order. */
        fun of(texts: List<String>): OwnerText = texts.fold(NONE, OwnerText::plus)

        // The tokens of [text] that can be a host's labels: its runs of letters, digits and hyphens, split at spaces, dots
        // and every other sign, without a spoken "dot", lowercased and in their ASCII form. A token that can be no label stays
        // as one that matches none, so that the tokens around it never meet.
        private fun hostTokens(text: String): List<String> =
            text.split(SEPARATORS).filter { it.isNotEmpty() }.map { ascii(it) ?: NO_LABEL }.filter { it != SPOKEN_DOT }

        // [label] lowercased and in its ASCII form, as a host carries it; null when it cannot be a host's label.
        private fun ascii(label: String): String? = try {
            IDN.toASCII(label.lowercase(Locale.ROOT)).lowercase(Locale.ROOT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
