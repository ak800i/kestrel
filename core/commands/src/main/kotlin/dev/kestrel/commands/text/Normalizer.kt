package dev.kestrel.commands.text

import java.text.Normalizer as Unicode
import java.util.Locale

/**
 * Folds every text that Kestrel compares into one form (design spec, section 9.6): spoken words, screen labels, app
 * names and contact names. It lowercases, writes Cyrillic in Latin letters, removes diacritics, deletes apostrophes, and
 * turns every other character that is not a letter or a digit into a space, with single spaces between words. "Okay" and
 * "O.K." become the word "ok".
 */
object Normalizer {
    fun normalize(text: String): String = withOffsets(text).text

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** The folded text, with the range of [text] that each folded character came from. */
    fun withOffsets(text: String): Folded {
        val folded = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var space = false
        var lastEnd = 0
        var index = 0
        while (index < text.length) {
            // A letter folds together with the combining marks after it, so that its range covers them and a decomposed
            // (NFD) letter folds as its composed form.
            var next = text.offsetByCodePoints(index, 1)
            while (next < text.length && isMark(text.codePointAt(next))) next = text.offsetByCodePoints(next, 1)
            for (char in fold(transliterate(text.substring(index, next)))) {
                if (char in APOSTROPHES) continue
                if (!char.isLetterOrDigit()) {
                    space = true
                    continue
                }
                if (space && folded.isNotEmpty()) {
                    folded.append(' ')
                    starts += lastEnd
                    ends += index
                }
                space = false
                folded.append(char)
                starts += index
                ends += next
                lastEnd = next
            }
            index = next
        }
        return okay(text, Folded(folded.toString(), starts.toIntArray(), ends.toIntArray()))
    }

    // The recogniser writes "okay" where a screen says "OK" or "O.K.": all three fold to the word "ok", which keeps the
    // offsets of the whole original word.
    private fun okay(original: String, folded: Folded): Folded {
        val text = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val words = folded.words
        var index = 0
        while (index < words.size) {
            val range = words[index]
            val word = folded.text.substring(range)
            val next = words.getOrNull(index + 1)
            val dotted = word == "o" && next != null && folded.text.substring(next) == "k" &&
                original.substring(folded.ends[range.first], folded.starts[next.first]) == "."
            if (text.isNotEmpty()) {
                // The space keeps the offsets of the separators before the word.
                text.append(' ')
                starts += folded.starts[range.first - 1]
                ends += folded.ends[range.first - 1]
            }
            when {
                dotted || word == "okay" -> {
                    val last = if (dotted) next.last else range.last
                    text.append("ok")
                    starts += folded.starts[range.first]
                    ends += folded.ends[range.first]
                    starts += folded.starts[if (dotted) last else range.first + 1]
                    ends += folded.ends[last]
                }
                else -> for (position in range) {
                    text.append(folded.text[position])
                    starts += folded.starts[position]
                    ends += folded.ends[position]
                }
            }
            index += if (dotted) 2 else 1
        }
        return Folded(text.toString(), starts.toIntArray(), ends.toIntArray())
    }

    /**
     * [text] composed (NFC) and in lower case, with Cyrillic letters written in Latin ones that may still carry
     * diacritics. Composing first makes a decomposed "й" or "č" the letter that the table and the phonetic key know.
     */
    internal fun transliterate(text: String): String = buildString {
        for (char in Unicode.normalize(text, Unicode.Form.NFC).lowercase(Locale.ROOT)) append(CYRILLIC[char] ?: char)
    }

    /** [text] without diacritics, with the explicit rules first. Apostrophes and separators stay. */
    internal fun fold(text: String): String = buildString {
        for (char in text) {
            val explicit = EXPLICIT[char]
            if (explicit != null) {
                append(explicit)
                continue
            }
            for (part in Unicode.normalize(char.toString(), Unicode.Form.NFD)) {
                if (!isMark(part.code)) append(part)
            }
        }
    }

    private fun isMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private val APOSTROPHES = setOf('\'', '\u2019', '\u2018', '\u02BC')

    private val EXPLICIT = mapOf('đ' to "dj", 'ł' to "l", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss")

    // The Serbian table, then the Russian and Ukrainian letters that it lacks. The hard and soft signs disappear.
    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "đ", 'е' to "e", 'ж' to "ž", 'з' to "z",
        'и' to "i", 'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj", 'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'ћ' to "ć", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "č", 'џ' to "dž", 'ш' to "š",
        'й' to "j", 'ё' to "e", 'э' to "e", 'ю' to "ju", 'я' to "ja", 'ы' to "y", 'щ' to "šč", 'ъ' to "", 'ь' to "",
        'є' to "je", 'ї' to "ji", 'і' to "i", 'ґ' to "g",
    )
}

/**
 * A folded text. The folded character `i` came from the original characters `starts[i] until ends[i]`; a space came
 * from the separators between two words.
 */
class Folded(val text: String, val starts: IntArray, val ends: IntArray) {
    /** Each word's range in [text]. */
    val words: List<IntRange> = buildList {
        var start = -1
        for (index in 0..text.length) {
            val letter = index < text.length && text[index] != ' '
            if (letter && start < 0) start = index
            if (!letter && start >= 0) {
                add(start until index)
                start = -1
            }
        }
    }

    /**
     * The range of the original text that made the folded characters `from until to`, with `from <= to`. An empty range
     * (`from == to`) gives the empty range where folded character `from` starts, or after the last folded character when
     * `from` is the length of [text] (at 0 when there is none).
     */
    fun source(from: Int, to: Int): IntRange {
        require(from in 0..to && to <= text.length) { "No folded characters $from until $to of ${text.length}" }
        if (from < to) return starts[from] until ends[to - 1]
        val at = if (from < text.length) starts[from] else ends.lastOrNull() ?: 0
        return at until at
    }

    /**
     * The range of the original text that made the words `fromWord until toWord`, with `fromWord <= toWord`. An empty range
     * (`fromWord == toWord`) gives the empty range where word `fromWord` starts, or after the last word when `fromWord` is
     * the number of [words].
     */
    fun span(fromWord: Int, toWord: Int): IntRange {
        require(fromWord in 0..toWord && toWord <= words.size) { "No words $fromWord until $toWord of ${words.size}" }
        if (fromWord == toWord) {
            val at = if (fromWord < words.size) words[fromWord].first else text.length
            return source(at, at)
        }
        return source(words[fromWord].first, words[toWord - 1].last + 1)
    }

    override fun toString() = "Folded(${text.length} characters, ${words.size} words)"
}
