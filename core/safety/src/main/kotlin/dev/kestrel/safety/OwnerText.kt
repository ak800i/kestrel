package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import java.net.IDN
import java.util.Locale

/**
 * The owner's own words in a task (D8 and D13 of sub-project 5; D8 of sub-project 7; spec 12.3): his command, the
 * conversation's earlier commands, his answers and his steering. Each text is kept as its folded words, in their order,
 * as the host names that it holds, and as its digits. Only his own texts are ever added, never one of the screen, a
 * data tool or the model, so what it holds is his, and every rule of the safety check that asks for his words reads it:
 * typing his words, a contact's number that he said, a search of his words and a link that he named. toString prints
 * only the count.
 */
class OwnerText private constructor(private val texts: List<Text>) {
    // One of his texts: its folded words, the host names that it holds, each as its labels, and its digits.
    private class Text(val words: List<String>, val hosts: List<List<String>>, val digits: String) {
        override fun equals(other: Any?): Boolean = other is Text && other.words == words && other.hosts == hosts && other.digits == digits

        override fun hashCode(): Int = 31 * (31 * words.hashCode() + hosts.hashCode()) + digits.hashCode()
    }

    /** Every folded word of his texts. */
    val words: Set<String> = texts.flatMapTo(mutableSetOf()) { it.words }

    /** The digits of each of his texts that holds any, in their order. */
    val digits: List<String> = texts.map { it.digits }.filter { it.isNotEmpty() }

    /** These words with his [text]. */
    operator fun plus(text: String): OwnerText = OwnerText(texts + Text(Normalizer.words(text), hosts(text), TaskSafety.digitsOf(text)))

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
     * end one of the host names that the text holds. Hyphens stay, so that "kestrel.example.com" never names the lookalike
     * `kestrel-example.com`; the whole host is needed, so that "wikipedia.org" names no subdomain of it; and the host ends
     * where his ends, so that "www.bbc.co.uk" names `bbc.co.uk` but never `bbc.co`.
     */
    fun namesHost(host: String): Boolean {
        val labels = ascii(host.trimEnd('.'))?.split('.') ?: return false
        if (labels.any { it.isEmpty() }) return false
        return texts.any { text -> text.hosts.any { it.size >= labels.size && it.takeLast(labels.size) == labels } }
    }

    override fun equals(other: Any?): Boolean = other is OwnerText && other.texts == texts

    override fun hashCode(): Int = texts.hashCode()

    override fun toString(): String = "OwnerText(texts=${texts.size})"

    companion object {
        // A spoken dot between a host's labels, a word that can be no label, the dot that joins typed labels, and a word.
        private const val SPOKEN_DOT = "dot"
        private const val NO_LABEL = ""
        private const val TYPED_DOT = "."
        private val WORD = Regex("""[\p{L}\p{N}\p{M}-]+""")

        val NONE = OwnerText(emptyList())

        /** The owner's [texts], in their order. */
        fun of(texts: List<String>): OwnerText = texts.fold(NONE, OwnerText::plus)

        /**
         * The host names that [text] holds, each as its labels: its words (runs of letters, digits and hyphens, after the
         * fold's apostrophes are deleted, so that "it's" stays one word) joined across a dot between them, or across a spoken
         * "dot" with only spaces around it. Any other sign or space ends a name, so ordinary words and the parts of a typed
         * link's path are names of one label each. Each label is lowercased and in its ASCII form; a word that can be no
         * label stays as one that matches none. A spoken "dot" that joins nothing is no label.
         */
        private fun hosts(text: String): List<List<String>> {
            val plain = text.filterNot { it in Normalizer.APOSTROPHES }
            val hosts = mutableListOf<MutableList<String>>()
            var host: MutableList<String>? = null
            var dotted = false
            var end = 0
            for (word in WORD.findAll(plain)) {
                val before = plain.substring(end, word.range.first)
                end = word.range.last + 1
                val spaced = before.isNotEmpty() && before.isBlank()
                if (word.value.lowercase(Locale.ROOT) == SPOKEN_DOT) {
                    dotted = host != null && !dotted && spaced
                    if (!dotted) host = null
                    continue
                }
                val label = ascii(word.value) ?: NO_LABEL
                val current = host
                if (current != null && (if (dotted) spaced else before == TYPED_DOT)) {
                    current += label
                } else {
                    host = mutableListOf(label).also { hosts += it }
                }
                dotted = false
            }
            return hosts
        }

        // [label] lowercased and in its ASCII form, as a host carries it; null when it cannot be a host's label.
        private fun ascii(label: String): String? = try {
            IDN.toASCII(label.lowercase(Locale.ROOT)).lowercase(Locale.ROOT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
