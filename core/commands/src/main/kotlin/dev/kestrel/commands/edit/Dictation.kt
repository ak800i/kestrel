package dev.kestrel.commands.edit

import java.util.Locale

/** One part of a dictation: text that replaces the selection, or a line break, which goes through the Enter key. */
sealed interface DictationPart {
    data class Text(val change: EditStep.Change) : DictationPart

    data object NewLine : DictationPart
}

/**
 * Turns dictated words into the text to insert (design spec, section 9.7). Kestrel types only the punctuation that the
 * owner says ("comma", "question mark", "new line" and the others) and drops the recogniser's own, which adds a full stop
 * to almost every short utterance; a mark inside a word, as in "example.com" or "3.5", stays. The first word of an
 * utterance loses its capital unless it is "I" or is written in capitals; the other words keep the recogniser's case. A
 * word gets a capital at the start of an empty field and after a full stop, question mark or exclamation mark, whether
 * the owner says it now or the field had it already; "dot" joins without a space and starts no sentence, as in
 * "example.com". Words that spell something, such as "capital p one two three", are joined without spaces, and in a
 * password field every word is, with its spoken punctuation as marks.
 */
object Dictation {
    /**
     * The parts that insert [spoken] at [state]'s selection, split at each spoken "new line" and "new paragraph". In a
     * [password] field the words are spelled and joined. [startsUtterance] is false for the words of "type X" and "insert
     * X", which do not start what the owner said.
     */
    fun insert(state: TextState, spoken: String, password: Boolean, startsUtterance: Boolean = true): List<DictationPart> {
        // An utterance that leaves no words, such as the recogniser's lone ".", changes nothing.
        if (words(spoken).isEmpty()) return emptyList()
        if (password) {
            val spelled = spell(words(spoken), password = true).orEmpty()
            val end = state.selectionStart + spelled.length
            return listOf(DictationPart.Text(EditStep.Change(state.selectionStart, state.selectionEnd, spelled, end, end)))
        }
        val change = render(state, spoken, startsUtterance)
        val pieces = change.replacement.split('\n')
        return buildList {
            var offset = change.start
            for ((index, piece) in pieces.withIndex()) {
                if (index > 0) {
                    add(DictationPart.NewLine)
                    offset++
                }
                // The first part also replaces the selection, even when it brings no text of its own.
                if (piece.isNotEmpty() || (index == 0 && change.end > change.start)) {
                    val end = if (index == 0) change.end else offset
                    add(DictationPart.Text(EditStep.Change(offset, end, piece, offset + piece.length, offset + piece.length)))
                }
                offset += piece.length
            }
        }
    }

    /**
     * One change that inserts [spoken] at [state]'s selection, with each line break as "\n". Without words it changes
     * nothing and keeps the selection.
     */
    fun render(state: TextState, spoken: String, startsUtterance: Boolean): EditStep.Change {
        if (words(spoken).isEmpty()) {
            return EditStep.Change(state.selectionStart, state.selectionStart, "", state.selectionStart, state.selectionEnd)
        }
        val before = state.text.substring(0, state.selectionStart)
        val after = state.text.substring(state.selectionEnd)
        val text = build(before, after, spoken, startsUtterance, capitals = true)
        // "Hello |" and "period": a closing mark takes the place of the one space that the field has before the cursor.
        val replacesSpace = text.firstOrNull()?.let { it in SPACELESS } == true && before.length >= 2 &&
            (before.last() == ' ' || before.last() == '\u00A0') && !before[before.length - 2].isWhitespace()
        val start = if (replacesSpace) state.selectionStart - 1 else state.selectionStart
        val end = start + text.length
        return EditStep.Change(start, state.selectionEnd, text, end, end)
    }

    /**
     * [spoken] with its spoken punctuation and without the recogniser's own, in the recogniser's case, for the new words
     * of "replace X with Y".
     */
    fun phrase(spoken: String): String = build("", "", spoken, startsUtterance = false, capitals = false)

    /** "Format email": lower case, "at" as "@", "dot" as ".", "underscore" as "_", "dash" and "hyphen" as "-", no spaces. */
    fun formatEmail(text: String): String = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString("") { word ->
        when (word.lowercase(Locale.ROOT).trim(',', ';', ':')) {
            "at" -> "@"
            "dot" -> "."
            "underscore" -> "_"
            "dash", "hyphen" -> "-"
            else -> word.lowercase(Locale.ROOT)
        }
    }

    /**
     * The text for [spoken] between [before] and [after]: a space before it when neither side is whitespace, a space
     * after it before a word, and with [capitals] a capital at the start of a sentence.
     */
    private fun build(before: String, after: String, spoken: String, startsUtterance: Boolean, capitals: Boolean): String {
        val words = words(spoken)
        val spelled = spell(words, password = false)
        val tokens = if (spelled != null) listOf(Token.Word(spelled)) else tokens(words)
        val text = StringBuilder()
        var quotes = before.count { it == '"' }
        var singles = before.indices.count { isLoneApostrophe(before, it) }
        var previous = when {
            before.isEmpty() || before.last().isWhitespace() -> Kind.BREAK
            before.last() in OPENERS || (before.last() == '"' && quotes % 2 == 1) -> Kind.OPENING
            // "john@|" or "my_|": the owner paused in the middle of an address or a name.
            before.last() in JOINERS && before.length >= 2 && before[before.length - 2].isLetterOrDigit() -> Kind.JOINING
            else -> Kind.WORD
        }
        // Spelled words go in exactly as spelled.
        var sentenceStart = capitals && spelled == null && startsSentence(before)
        var first = startsUtterance && spelled == null
        for (token in tokens) {
            when (token) {
                is Token.Word -> {
                    var word = token.text
                    if (first && !keepsCapital(word)) word = word.replaceFirstChar { it.lowercase(Locale.ROOT) }
                    first = false
                    if (sentenceStart) word = word.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    sentenceStart = false
                    if (previous == Kind.WORD || previous == Kind.CLOSING) text.append(' ')
                    text.append(word)
                    previous = Kind.WORD
                }
                is Token.Mark -> {
                    val kind = when (token.kind) {
                        Kind.QUOTE -> if (quotes % 2 == 0) Kind.OPENING else Kind.CLOSING
                        Kind.SINGLE_QUOTE -> if (singles++ % 2 == 0) Kind.OPENING else Kind.CLOSING
                        else -> token.kind
                    }
                    if (token.text == "\"") quotes++
                    val spaced = kind == Kind.WORD || (kind == Kind.OPENING && previous != Kind.OPENING && previous != Kind.JOINING)
                    if (spaced && (previous == Kind.WORD || previous == Kind.CLOSING)) text.append(' ')
                    text.append(token.text)
                    when (token.text) {
                        // "Dot" joins, as in "example.com", and starts no sentence.
                        ".", "!", "?" -> sentenceStart = capitals && kind == Kind.CLOSING
                        ",", ";", ":" -> sentenceStart = false
                    }
                    previous = kind
                }
                Token.Break -> {
                    text.append('\n')
                    previous = Kind.BREAK
                }
            }
        }
        // A space after the new words when they go in before a word of the field.
        if (after.firstOrNull()?.isLetterOrDigit() == true && (previous == Kind.WORD || previous == Kind.CLOSING)) text.append(' ')
        return text.toString()
    }

    /**
     * The words of [spoken] without the recogniser's punctuation. Marks between digits stay, as in "3.5" and "10:30", and
     * so do ".", ":" and "@" between letters or digits, as in "example.com" and "john@example.com".
     */
    private fun words(spoken: String): List<String> = buildList {
        for (raw in spoken.split(WHITESPACE)) {
            val piece = StringBuilder()
            for ((index, char) in raw.withIndex()) {
                val inside = index > 0 && index < raw.length - 1
                val betweenDigits = inside && raw[index - 1].isDigit() && raw[index + 1].isDigit()
                val betweenWords = inside && raw[index - 1].isLetterOrDigit() && raw[index + 1].isLetterOrDigit()
                val kept = (char in DIGIT_MARKS && betweenDigits) || (char in WORD_MARKS && betweenWords)
                val dropped = (char in DROPPED && !kept) ||
                    (char in DASHES && raw.length == 1) ||
                    (char in LEADING_QUOTES && index == 0)
                if (dropped) {
                    if (piece.isNotEmpty()) add(piece.toString())
                    piece.clear()
                } else {
                    piece.append(char)
                }
            }
            if (piece.isNotEmpty()) add(piece.toString())
        }
    }

    /** The words with spoken punctuation turned into marks. */
    private fun tokens(words: List<String>): List<Token> = buildList {
        val lower = words.map { it.lowercase(Locale.ROOT) }
        var index = 0
        while (index < words.size) {
            val match = MARKS.firstOrNull { (phrase, _) ->
                index + phrase.size <= words.size && phrase.indices.all { lower[index + it] == phrase[it] }
            }
            if (match == null) {
                add(Token.Word(words[index]))
                index++
            } else {
                addAll(match.second)
                index += match.first.size
            }
        }
    }

    /**
     * The words joined without spaces when they spell something: at least two words, and each one a single letter, a
     * number, a digit word or "capital" with a letter. In a [password] field every word counts, the names of letters
     * ("bee", "double u") too, spoken punctuation goes in as its mark ("exclamation mark" as "!"), and the words are in
     * lower case unless "capital" comes before them.
     */
    private fun spell(words: List<String>, password: Boolean): String? {
        if (!password && words.size < 2) return null
        val spelled = StringBuilder()
        var index = 0
        while (index < words.size) {
            // A password may hold any character, so its spoken punctuation goes in as marks.
            val mark = if (password) passwordMark(words, index) else null
            if (mark != null) {
                spelled.append(mark.second)
                index += mark.first
                continue
            }
            val word = words[index].lowercase(Locale.ROOT)
            val next = words.getOrNull(index + 1)?.lowercase(Locale.ROOT)
            val capital = if (word == "capital" && next != null) letter(next, password) else null
            when {
                capital != null -> {
                    spelled.append(capital.uppercase(Locale.ROOT))
                    index++
                }
                password && word == "double" && (next == "u" || next == "you") -> {
                    spelled.append('w')
                    index++
                }
                word.all { it.isDigit() } -> spelled.append(word)
                word in DIGITS -> spelled.append(DIGITS.getValue(word))
                letter(word, password) != null -> spelled.append(letter(word, password))
                password -> spelled.append(word)
                else -> return null
            }
            index++
        }
        return spelled.toString()
    }

    /**
     * The spoken mark at [index] of [words], or a symbol that only a password takes by name ("hash", "dollar sign"), as its
     * number of words and its characters; a line break is none.
     */
    private fun passwordMark(words: List<String>, index: Int): Pair<Int, String>? {
        val (phrase, tokens) = MARKS.firstOrNull { (phrase, tokens) ->
            tokens.all { it is Token.Mark } && index + phrase.size <= words.size &&
                phrase.indices.all { words[index + it].lowercase(Locale.ROOT) == phrase[it] }
        } ?: return PASSWORD_SYMBOLS.firstNotNullOfOrNull { (phrase, symbol) ->
            val fits = index + phrase.size <= words.size && phrase.indices.all { words[index + it].lowercase(Locale.ROOT) == phrase[it] }
            if (fits) phrase.size to symbol else null
        }
        return phrase.size to tokens.joinToString("") { (it as Token.Mark).text }
    }

    // Symbols that only a password field takes by name, where no word is meant as a word; elsewhere "plus" or "hash"
    // stay words.
    private val PASSWORD_SYMBOLS: List<Pair<List<String>, String>> = listOf(
        "dollar sign" to "$", "plus sign" to "+", "equals sign" to "=", "hash" to "#", "asterisk" to "*",
    ).map { (phrase, symbol) -> phrase.split(' ') to symbol }

    private fun letter(word: String, password: Boolean): String? = when {
        word.length == 1 && word[0].isLetter() -> word
        password -> LETTER_NAMES[word]
        else -> null
    }

    private fun keepsCapital(word: String): Boolean =
        word == "I" || I_CONTRACTIONS.matches(word) || (word.count { it.isLetter() } > 1 && word.none { it.isLowerCase() })

    /** Whether the text before the cursor is empty or ends a sentence, also before an opening quote, as in `Done. "`. */
    private fun startsSentence(before: String): Boolean {
        val trimmed = before.trimEnd().trimEnd(*CLOSERS).trimEnd()
        return trimmed.isEmpty() || trimmed.last() in ".!?"
    }

    private fun isLoneApostrophe(text: String, index: Int): Boolean =
        text[index] == '\'' &&
            !(index > 0 && text[index - 1].isLetterOrDigit() && index < text.length - 1 && text[index + 1].isLetterOrDigit())

    /** How a token joins its neighbours. A [QUOTE] or [SINGLE_QUOTE] opens or closes by how many came before it. */
    private enum class Kind { WORD, CLOSING, OPENING, JOINING, QUOTE, SINGLE_QUOTE, BREAK }

    private sealed interface Token {
        data class Word(val text: String) : Token

        data class Mark(val text: String, val kind: Kind) : Token

        data object Break : Token
    }

    private fun mark(text: String, kind: Kind) = listOf(Token.Mark(text, kind))

    // The spoken punctuation; the longest phrase that matches wins.
    private val MARKS: List<Pair<List<String>, List<Token>>> = listOf(
        "open double quote" to mark("\"", Kind.OPENING),
        "close double quote" to mark("\"", Kind.CLOSING),
        "new paragraph" to listOf(Token.Break, Token.Break),
        "new line" to listOf(Token.Break),
        "full stop" to mark(".", Kind.CLOSING),
        "question mark" to mark("?", Kind.CLOSING),
        "exclamation mark" to mark("!", Kind.CLOSING),
        "exclamation point" to mark("!", Kind.CLOSING),
        "double quote" to mark("\"", Kind.QUOTE),
        "open quote" to mark("\"", Kind.OPENING),
        "close quote" to mark("\"", Kind.CLOSING),
        "single quote" to mark("'", Kind.SINGLE_QUOTE),
        "open parenthesis" to mark("(", Kind.OPENING),
        "open bracket" to mark("(", Kind.OPENING),
        "close parenthesis" to mark(")", Kind.CLOSING),
        "close bracket" to mark(")", Kind.CLOSING),
        "at sign" to mark("@", Kind.JOINING),
        "percent sign" to mark("%", Kind.CLOSING),
        "comma" to mark(",", Kind.CLOSING),
        "period" to mark(".", Kind.CLOSING),
        "dot" to mark(".", Kind.JOINING),
        "colon" to mark(":", Kind.CLOSING),
        "semicolon" to mark(";", Kind.CLOSING),
        "quote" to mark("\"", Kind.QUOTE),
        "apostrophe" to mark("'", Kind.JOINING),
        "hyphen" to mark("-", Kind.JOINING),
        "dash" to mark("-", Kind.WORD),
        "slash" to mark("/", Kind.JOINING),
        "underscore" to mark("_", Kind.JOINING),
        "ampersand" to mark("&", Kind.WORD),
    ).map { (phrase, tokens) -> phrase.split(' ') to tokens }.sortedByDescending { it.first.size }

    private val DIGITS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9",
    )

    // The names of letters count only in password fields, where no word is meant as a word.
    private val LETTER_NAMES = mapOf(
        "ay" to "a", "bee" to "b", "be" to "b", "see" to "c", "sea" to "c", "cee" to "c", "dee" to "d", "ef" to "f",
        "eff" to "f", "gee" to "g", "aitch" to "h", "eye" to "i", "jay" to "j", "kay" to "k", "el" to "l", "ell" to "l",
        "em" to "m", "en" to "n", "oh" to "o", "pee" to "p", "cue" to "q", "queue" to "q", "ar" to "r", "are" to "r",
        "ess" to "s", "tee" to "t", "tea" to "t", "you" to "u", "vee" to "v", "ex" to "x", "why" to "y", "wye" to "y",
        "zed" to "z", "zee" to "z",
    )

    private val WHITESPACE = Regex("\\s+")
    private val I_CONTRACTIONS = Regex("I['\u2019](m|ll|ve|d)")
    private val DROPPED = setOf('.', ',', '?', '!', ';', ':', '"', '\u201C', '\u201D', '\u201E', '\u2026', '\u00AB', '\u00BB')
    private val DIGIT_MARKS = setOf('.', ',', ':')
    private val WORD_MARKS = setOf('.', ':', '@')

    // The marks that join what comes after them when they follow a letter or digit at the cursor; "." is not one of
    // them, because "Done." ends a sentence.
    private val JOINERS = setOf('@', '_', '/', '-')
    private val DASHES = setOf('-', '\u2013', '\u2014')
    private val LEADING_QUOTES = setOf('\'', '\u2018')
    private val OPENERS = setOf('(', '[', '{')

    // The closing marks that take the place of a space before the cursor.
    private val SPACELESS = setOf('.', ',', '?', '!', ';', ':', ')', '%')
    private val CLOSERS = charArrayOf('"', '\'', '\u201D', '\u2019', ')', ']')
}
