package dev.kestrel.app.listen

/**
 * How a transcript differs from what was said (design spec, section 16.4): the [words] that were said, and the
 * [substitutions], [deletions] and [insertions] of the alignment with the fewest changes. Letter case and punctuation do
 * not count, and an apostrophe stays in its word, as in Requests. A number counts the same in words and in digits, up to
 * 99, because the recogniser may write either: "six forty-five" is "6:45", and "twenty five" is "25". The word error
 * rate is [errors] divided by [words]. The evaluation hook of debug builds uses it. It holds only counts, so it may be
 * printed.
 */
internal data class WordErrors(val words: Int, val substitutions: Int, val deletions: Int, val insertions: Int) {
    val errors: Int get() = substitutions + deletions + insertions

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+(?:'[\p{L}\p{N}]+)*""")

        fun count(said: String, heard: String): WordErrors {
            val a = words(said)
            val b = words(heard)
            // cost[i][j]: the fewest changes that turn the first i words that were said into the first j words heard.
            val cost = Array(a.size + 1) { i -> IntArray(b.size + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
            for (i in 1..a.size) {
                for (j in 1..b.size) {
                    val change = if (a[i - 1] == b[j - 1]) 0 else 1
                    cost[i][j] = minOf(cost[i - 1][j - 1] + change, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
                }
            }
            // Back along one cheapest alignment, counting its changes by kind.
            var i = a.size
            var j = b.size
            var substitutions = 0
            var deletions = 0
            var insertions = 0
            while (i > 0 || j > 0) {
                val change = if (i > 0 && j > 0 && a[i - 1] == b[j - 1]) 0 else 1
                when {
                    i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + change -> {
                        substitutions += change
                        i--
                        j--
                    }
                    i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> {
                        deletions++
                        i--
                    }
                    else -> {
                        insertions++
                        j--
                    }
                }
            }
            return WordErrors(a.size, substitutions, deletions, insertions)
        }

        private val UNITS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        )

        private fun words(text: String): List<String> =
            digits(WORD.findAll(text.lowercase()).map { it.value.replace("'", "") }.toList())

        // Writes the numbers up to 99 in digits: "forty five" and "forty-five", which are two words here, become "45".
        private fun digits(words: List<String>): List<String> {
            val result = mutableListOf<String>()
            var at = 0
            while (at < words.size) {
                val tens = TENS[words[at]]
                val unit = UNITS.indexOf(words[at])
                val next = words.getOrNull(at + 1)?.let { UNITS.indexOf(it) } ?: -1
                when {
                    tens != null && next in 1..9 -> {
                        result += (tens + next).toString()
                        at += 2
                    }
                    tens != null -> {
                        result += tens.toString()
                        at++
                    }
                    unit >= 0 -> {
                        result += unit.toString()
                        at++
                    }
                    else -> {
                        result += words[at]
                        at++
                    }
                }
            }
            return result
        }
    }
}
