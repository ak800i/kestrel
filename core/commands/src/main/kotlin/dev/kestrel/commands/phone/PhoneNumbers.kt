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
     * separators are ignored.
     */
    fun digits(text: String): String? {
        val number = StringBuilder()
        for (token in TOKEN.findAll(text.lowercase()).map { it.value }) {
            when {
                token[0].isDigit() -> token.forEach { number.append(Character.forDigit(Character.digit(it, 10), 10)) }
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
     * between them. Dates such as "17.11.2026", "17. 11. 2026." and "17/11/26", also with a time after them, are skipped.
     */
    fun find(text: String): List<IntRange> = CANDIDATE.findAll(text).filter { match ->
        match.value.count(Char::isDigit) in 6..15 && !DATE.matches(match.value)
    }.map { it.range }.toList()

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
