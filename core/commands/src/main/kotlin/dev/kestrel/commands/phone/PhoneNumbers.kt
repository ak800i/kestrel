package dev.kestrel.commands.phone

/**
 * Phone numbers in speech and on the screen (design spec, section 13.6). Nothing here keeps or prints a number.
 */
object PhoneNumbers {
    private val TOKEN = Regex("""[+*#]|\p{L}+|\d+""")
    private val DIGIT_WORDS = mapOf(
        "zero" to '0', "oh" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4', "five" to '5', "six" to '6',
        "seven" to '7', "eight" to '8', "nine" to '9',
    )

    // An optional "+", then digits with spaces, dashes, dots, slashes or brackets between them, not inside a word or a
    // number. Serbian numbers are often written as 064/123-45-67.
    private val CANDIDATE = Regex("""(?<![\p{L}\p{N}+])\+?[\d(][\d \u00A0\-./()]*\d(?![\p{L}\p{N}])""")

    private const val DAY = """(?:0?[1-9]|[12]\d|3[01])"""
    private const val MONTH = """(?:0?[1-9]|1[0-2])"""
    private const val YEAR = """(?:\d{2}|\d{4})"""

    // A date such as 17.11.2026, 17. 11. 2026., 2026-11-17, 17/11/26 or 11/17/26, also with a time or words after it.
    private val DATE =
        Regex("""(?:$DAY[./-] ?$MONTH[./-] ?$YEAR|$MONTH[./-]$DAY[./-]$YEAR|\d{4}[./-]$MONTH[./-]$DAY)\.?(?:[ \u00A0].*)?""")

    // A group of 1 or 2 digits and the spaces after it, before more digits.
    private val LEADING_GROUP = Regex("""^\d{1,2}[ \u00A0]+(?=[\d(])""")

    // An amount such as 1.250.000, and a decimal such as a coordinate's 44.812345.
    private val THOUSANDS = Regex("""[1-9]\d{0,2}(?:\.\d{3})+""")
    private val DECIMAL = Regex("""\d{1,3}\.\d{4,}""")
    private val COMMA_AFTER = Regex(""",\d""")

    // The parts of a stored number: an extension's word, digits, words, or one other character.
    private val STORED = Regex("""extension|ext\.?|\d+|\p{L}+|[^\p{L}\d]""")
    private val EXTENSION = setOf("extension", "ext", "ext.", "x", "p")
    private val WAIT = setOf("w")
    private const val SEPARATORS = " \u00A0-./()"

    /** Numbers shorter than this are emergency and short codes, which Kestrel never calls itself. */
    const val SHORTEST_AUTO_DIAL = 5

    /**
     * The number that [text] says, as "+", digits, "*" and "#", or null when [text] says anything else. Digits and digit
     * words count, "plus" is "+" at the start, "star" is "*", "hash" and "pound" are "#"; spaces, dashes and other
     * separators are ignored. A digit of another script than ASCII's makes [text] no number.
     */
    fun digits(text: String): String? {
        // A digit of another script is refused, not skipped, so that no other number comes out of the text.
        if (text.any { it.isDigit() && it !in '0'..'9' }) return null
        val number = StringBuilder()
        for (token in TOKEN.findAll(text.lowercase()).map { it.value }) {
            when {
                token[0].isDigit() -> number.append(token)
                token == "+" || token == "plus" -> if (number.isEmpty()) number.append('+') else return null
                token == "*" || token == "star" -> number.append('*')
                token == "#" || token == "hash" || token == "pound" -> number.append('#')
                token in DIGIT_WORDS -> number.append(DIGIT_WORDS.getValue(token))
                else -> return null
            }
        }
        return number.toString().takeIf { it.any(Char::isDigit) }
    }

    /**
     * Where [text] shows phone numbers: an optional "+", then 6 to 15 digits with spaces, dashes, dots, slashes or brackets
     * between them. Dates such as "17.11.2026", "17. 11. 2026." and "17/11/26", also with a time after them, are skipped,
     * and so are amounts and coordinates, whose dots and commas separate thousands or decimals ("1.250.000,00",
     * "44.812345"). A group of 1 or 2 digits right after a word, such as "Room 12" before a number, is no part of it.
     */
    fun find(text: String): List<IntRange> = CANDIDATE.findAll(text).mapNotNull { match ->
        var range = match.range
        if (followsWord(text, range.first)) {
            LEADING_GROUP.find(match.value)?.let { range = range.first + it.value.length..range.last }
        }
        val value = text.substring(range)
        range.takeIf { value.count(Char::isDigit) in 6..15 && !DATE.matches(value) && !amount(text, range, value) }
    }.toList()

    // Whether the last character before [start], after any spaces, is a letter.
    private fun followsWord(text: String, start: Int): Boolean = text.substring(0, start).trimEnd().lastOrNull()?.isLetter() == true

    // Thousands groups after a first group that does not start with 0, a decimal, or digits that a decimal comma follows or
    // a thousands comma comes before.
    private fun amount(text: String, range: IntRange, value: String): Boolean =
        THOUSANDS.matches(value) || DECIMAL.matches(value) || COMMA_AFTER.matchesAt(text, range.last + 1) ||
            range.first >= 2 && text[range.first - 1] == ',' && text[range.first - 2].isDigit()

    /**
     * A contact's stored number as the phone app takes it in a `tel:` link: digits, a leading "+", "*" and "#", and a pause
     * (",") or a wait (";") before the digits that the phone app sends after the call has started. An extension ("ext.",
     * "x") and the letter "p" become a pause, and "w" a wait, so that the phone app dials the main number and sends the
     * rest itself; spaces, dashes, dots, slashes and brackets go. Null for anything else, such as letters of a word, which
     * Kestrel never turns into another number.
     */
    fun dialable(stored: String): String? {
        val number = StringBuilder()
        for (token in STORED.findAll(stored.lowercase()).map { it.value }) {
            when {
                token[0].isDigit() -> number.append(token)
                token == "+" -> if (number.isEmpty()) number.append('+') else return null
                token == "*" || token == "#" -> number.append(token)
                token == "," || token in EXTENSION -> if (number.isNotEmpty()) number.append(',') else return null
                token == ";" || token in WAIT -> if (number.isNotEmpty()) number.append(';') else return null
                token in SEPARATORS -> Unit
                else -> return null
            }
        }
        return number.toString().takeIf { it.any(Char::isDigit) }
    }

    /** The number that the phone app dials first: [digits] before a pause or a wait. */
    fun mainNumber(digits: String): String = digits.substringBefore(',').substringBefore(';')

    /**
     * Whether Kestrel may press Call for [digits] itself: digits with at most a leading "+", a pause or a wait, and at least
     * [SHORTEST_AUTO_DIAL] digits before the pause, so that no emergency or short code qualifies, and no "*" or "#", which
     * codes that change the phone's settings use. The caller also asks Android whether the main number is an emergency
     * number.
     */
    fun mayAutoDial(digits: String): Boolean =
        digits.all { it.isDigit() || it == '+' || it == ',' || it == ';' } && mainNumber(digits).count(Char::isDigit) >= SHORTEST_AUTO_DIAL
}
