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

    /**
     * The answer to "Which one?": "2", "number 2", "tap 2", "tap on 2", or a lone "to", also with polite words around it
     * ("can you tap 2?"). Every answer that [CommandParser] reads as a tap on a number is that number.
     */
    fun answer(text: String): Int? {
        val all = Normalizer.words(text)
        if (all.isEmpty()) return null
        val from = CommandParser.politeStart(all, 0, all.size)
        var words = all.subList(from, CommandParser.politeEnd(all, from, all.size))
        if (words.size > 1 && words[0] in TAP_VERBS) {
            words = words.drop(1)
            // "Tap on 2", as the grammar reads it.
            if (words.size > 1 && words[0] == "on") words = words.drop(1)
        }
        return number(words, lone = true)
    }

    /**
     * Whether [command] names an element or a square by its number, with any verb: "long press 2", "check 2", "drag 2 to
     * 5", "what is 2", a bare number, and the like. An answer to "Which one?" that does so ends the question, because
     * its number would otherwise name an element of "show numbers" or a square of the grid (design spec, section 9.4).
     */
    fun namesNumber(command: Command?): Boolean = when (command) {
        is Command.Bare -> command.number != null
        is Command.WhatIs -> true
        is Command.Tap -> command.target is Target.Number
        is Command.LongPress -> command.target is Target.Number
        is Command.Check -> command.target is Target.Number
        is Command.Switch -> command.target is Target.Number
        is Command.Expand -> command.target is Target.Number
        is Command.Collapse -> command.target is Target.Number
        is Command.Dismiss -> command.target is Target.Number
        is Command.Drag -> command.from is Target.Number || command.to is Target.Number
        is Command.Scroll -> command.target is Target.Number
        is Command.ScrollToEnd -> command.target is Target.Number
        is Command.Swipe -> command.target is Target.Number
        is Command.Pinch -> command.target is Target.Number
        is Command.Slider -> command.target is Target.Number
        is Command.CommandsFor -> command.target is Target.Number
        is TextCommand.StartEditing -> command.target is Target.Number
        else -> false
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
                    // "One hundred zero" is no number, as "twenty zero" is not.
                    if (ones == 0 && value > 0) return null
                    value += ones
                    index++
                }
                else -> return null
            }
        }
        return if (index == words.size) value else null
    }
}
