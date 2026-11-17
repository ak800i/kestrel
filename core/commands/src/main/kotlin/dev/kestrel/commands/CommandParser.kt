package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/**
 * Reads an instant command from a transcript (design spec, section 9.5). Kestrel's own phrases, which start with its
 * name, never reach it. A command matches only the whole utterance. The fixed phrases of every group come first
 * ([GrammarScreen], [GrammarText], [GrammarPhone]), then the rules with a target or a payload ([GrammarPhone],
 * [GrammarText], [GrammarScreen]); each group's targeted rules return null for a target that is not theirs. Anything else
 * is [Command.Bare]. Only a [Command.Bare] is read again without the polite words around it.
 */
object CommandParser {
    internal val POLITE_WORDS = setOf("please", "now", "ok", "okay")
    private val POLITE_PAIRS = listOf(listOf("could", "you"), listOf("can", "you"))

    /** The command in [text], or null when [text] has no words. */
    fun parse(text: String): Command? {
        val words = Normalizer.words(text)
        if (words.isEmpty()) return null
        val whole = read(words, text)
        if (whole !is Command.Bare) return whole
        var from = 0
        var to = words.size
        while (true) {
            val pair = POLITE_PAIRS.firstOrNull { to - from > it.size && words.subList(from, from + it.size) == it }
            from += when {
                pair != null -> pair.size
                to - from > 1 && words[from] in POLITE_WORDS -> 1
                else -> break
            }
        }
        while (true) {
            val pair = POLITE_PAIRS.firstOrNull { to - from > it.size && words.subList(to - it.size, to) == it }
            to -= when {
                pair != null -> pair.size
                to - from > 1 && words[to - 1] in POLITE_WORDS -> 1
                else -> break
            }
        }
        if (from == 0 && to == words.size) return whole
        val inner = cutWords(text, from, to)
        val again = read(Normalizer.words(inner), inner)
        return if (again is Command.Bare) whole else again
    }

    private fun read(words: List<String>, text: String): Command =
        GrammarScreen.fixed(words, text) ?: GrammarText.fixed(words, text) ?: GrammarPhone.fixed(words, text)
            ?: GrammarPhone.targeted(words, text) ?: GrammarText.targeted(words, text)
            ?: GrammarScreen.targeted(words, text) ?: Command.Bare.of(text.trim())
}

/**
 * The original text of the words `from until to` of `Normalizer.words(text)`, with the recogniser's case and the
 * punctuation between those words.
 */
internal fun cutWords(text: String, from: Int, to: Int): String = text.substring(Normalizer.withOffsets(text).span(from, to))

/** Reads the words of a command that name its target. */
internal object TargetWords {
    /**
     * The words `from until to` as a target: a spoken number (a lone "to" counts, because a verb comes before it) as
     * [Target.Number], otherwise their original text as [Target.Label]. Null when there are no words.
     */
    fun read(words: List<String>, text: String, from: Int, to: Int = words.size): Target? {
        if (from >= to) return null
        // "Tap 5 please": a polite word after a number is no part of it. A label keeps its words (design D5).
        var end = to
        while (end - from > 1 && words[end - 1] in CommandParser.POLITE_WORDS) end--
        SpokenNumbers.number(words.subList(from, end), lone = true)?.let { return Target.Number(it) }
        return Target.Label(cutWords(text, from, to))
    }
}
