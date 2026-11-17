package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/** Spoken numbers (design spec, section 9.5): digits, "number 21", and words up to 999. */
object SpokenNumbers {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { it.value to it.index }
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90,
    )

    // What the recogniser writes for a lone number that the owner said.
    private val HOMOPHONES = mapOf("to" to 2, "too" to 2, "for" to 4, "won" to 1, "ate" to 8)
    private val TAP_VERBS = setOf("tap", "click", "press", "touch", "hit")

    /**
     * The number that [words] say, or null. [words] are folded words; a leading "number" is allowed. With [lone], a single
     * "to" or "too" counts as 2, "for" as 4, "won" as 1 and "ate" as 8: right after a verb or "number" (which allows them
     * without [lone]), and as the whole answer to a question.
     */
    fun number(words: List<String>, lone: Boolean = false): Int? {
        val numbered = words.size > 1 && words[0] == "number"
        val rest = if (numbered) words.drop(1) else words
        if (rest.isEmpty()) return null
        if (rest.size == 1) {
            val word = rest[0]
            if (word.length <= 6 && word.all { it in '0'..'9' }) return word.toInt()
            // "Number to" is number two, also as a bare number.
            if (lone || numbered) HOMOPHONES[word]?.let { return it }
        }
        return words(rest)
    }

    /** The answer to "Which one?": "2", "number 2", "tap 2", or a lone "to", also with polite words around it. */
    fun answer(text: String): Int? {
        var words = Normalizer.words(text)
        while (words.size > 1 && words.first() in CommandParser.POLITE_WORDS) words = words.drop(1)
        while (words.size > 1 && words.last() in CommandParser.POLITE_WORDS) words = words.dropLast(1)
        val rest = if (words.size > 1 && words[0] in TAP_VERBS) words.drop(1) else words
        return number(rest, lone = true)
    }

    // [ones "hundred" ["and"]] [tens [ones]] or [ones], with every word used.
    private fun words(words: List<String>): Int? {
        var index = 0
        var value = 0
        val hundreds = ONES[words[0]]
        if (words.size >= 2 && hundreds != null && hundreds in 1..9 && words[1] == "hundred") {
            value = hundreds * 100
            index = 2
            if (index < words.size && words[index] == "and") {
                index++
                if (index == words.size) return null
            }
        }
        if (index < words.size) {
            val tens = TENS[words[index]]
            val ones = ONES[words[index]]
            when {
                tens != null -> {
                    value += tens
                    index++
                    val unit = words.getOrNull(index)?.let { ONES[it] }
                    if (unit != null && unit in 1..9) {
                        value += unit
                        index++
                    }
                }
                ones != null -> {
                    value += ones
                    index++
                }
                else -> return null
            }
        }
        return if (index == words.size) value else null
    }
}
