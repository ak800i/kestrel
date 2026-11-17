package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/**
 * Reads an instant command from a transcript (design spec, section 9.5). Kestrel's own phrases, which start with its
 * name, never reach it. A command matches only the whole utterance. The fixed phrases of every group come first
 * ([GrammarScreen], [GrammarText], [GrammarPhone]), then the rules with a target or a payload ([GrammarPhone],
 * [GrammarText], [GrammarScreen]); each group's targeted rules return null for a target that is not theirs. Anything else
 * is [Command.Bare]. Only a [Command.Bare] is read again without the polite words around it: first without those at the
 * start, then also without those at the end.
 */
object CommandParser {
    // In their folded form: the normaliser folds "okay", "OK" and "O.K." to "ok".
    internal val POLITE_WORDS = setOf("please", "now", "ok")
    private val POLITE_PAIRS = listOf(listOf("could", "you"), listOf("can", "you"))

    /** The command in [text], or null when [text] has no words. */
    fun parse(text: String): Command? {
        val words = Normalizer.words(text)
        if (words.isEmpty()) return null
        val whole = read(words, text)
        if (whole !is Command.Bare) return whole
        // The polite words at the start go first, and those at the end only when that is not enough, so that "Please tap
        // Send now" keeps "Send now" (design D5: polite words never leave a payload).
        val from = politeStart(words, 0, words.size)
        if (from > 0) readAgain(text, from, words.size)?.let { return it }
        val to = politeEnd(words, from, words.size)
        if (to == words.size) return whole
        return readAgain(text, from, to) ?: whole
    }

    /** The index of the first word of `words.subList(from, to)` after its polite words, with at least [keep] words left. */
    internal fun politeStart(words: List<String>, from: Int, to: Int, keep: Int = 1): Int {
        var start = from
        while (true) {
            val pair = POLITE_PAIRS.firstOrNull { to - start - it.size >= keep && words.subList(start, start + it.size) == it }
            start += when {
                pair != null -> pair.size
                to - start - 1 >= keep && words[start] in POLITE_WORDS -> 1
                else -> return start
            }
        }
    }

    /** The end of `words.subList(from, to)` without its polite words at the end, with at least [keep] words left. */
    internal fun politeEnd(words: List<String>, from: Int, to: Int, keep: Int = 1): Int {
        var end = to
        while (true) {
            val pair = POLITE_PAIRS.firstOrNull { end - from - it.size >= keep && words.subList(end - it.size, end) == it }
            end -= when {
                pair != null -> pair.size
                end - from - 1 >= keep && words[end - 1] in POLITE_WORDS -> 1
                else -> return end
            }
        }
    }

    /** Whether the words `from until to` are only polite words, or none. */
    internal fun politeOnly(words: List<String>, from: Int, to: Int): Boolean = politeEnd(words, from, to, keep = 0) == from

    private fun readAgain(text: String, from: Int, to: Int): Command? {
        val inner = cutWords(text, from, to)
        return read(Normalizer.words(inner), inner).takeIf { it !is Command.Bare }
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
        val end = CommandParser.politeEnd(words, from, to)
        SpokenNumbers.number(words.subList(from, end), lone = true)?.let { return Target.Number(it) }
        return Target.Label(cutWords(text, from, to))
    }
}
