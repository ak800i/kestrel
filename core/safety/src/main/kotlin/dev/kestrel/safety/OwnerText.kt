package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer

/**
 * The owner's own words in a task (D8 and D13 of sub-project 5; D8 of sub-project 7; spec 12.3): his command, the
 * conversation's earlier commands, his answers and his steering, each kept as the folded words of its text, in their
 * order, and its digits. Only his own texts are ever added, never one of the screen, a data tool or the model, so what it
 * holds is his, and every rule of the safety check that asks for his words reads it: typing his words, a contact's number
 * that he said, a search of his words and a link that he named. toString prints only the count.
 */
class OwnerText private constructor(private val texts: List<List<String>>, val digits: List<String>) {
    /** Every folded word of his texts. */
    val words: Set<String> = texts.flatMapTo(mutableSetOf()) { it }

    /** These words with his [text]. */
    operator fun plus(text: String): OwnerText =
        OwnerText(texts + listOf(Normalizer.words(text)), digits + listOf(TaskSafety.digitsOf(text)).filter { it.isNotEmpty() })

    /**
     * Whether he named [phrase] in one of his texts: its folded words in a row there, or all of them run together as one
     * of its words ("wikipedia.org" said "wikipedia org", or typed). A phrase never spans two of his texts.
     */
    fun names(phrase: String): Boolean {
        val words = Normalizer.words(phrase)
        if (words.isEmpty()) return false
        val joined = words.joinToString("")
        return texts.any { text -> text.windowed(words.size).any { it == words } || joined in text }
    }

    override fun equals(other: Any?): Boolean = other is OwnerText && other.texts == texts && other.digits == digits

    override fun hashCode(): Int = 31 * texts.hashCode() + digits.hashCode()

    override fun toString(): String = "OwnerText(texts=${texts.size})"

    companion object {
        val NONE = OwnerText(emptyList(), emptyList())

        /** The owner's [texts], in their order. */
        fun of(texts: List<String>): OwnerText = texts.fold(NONE, OwnerText::plus)
    }
}
