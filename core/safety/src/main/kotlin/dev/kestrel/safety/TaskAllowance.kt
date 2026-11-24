package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer

/**
 * Which of sending, calls and deleting the owner's own words ask for (design D13 of sub-project 5; decision 6; research
 * r52's tool filter). It reads verbs in a command position, never nouns, and never screen text: [from] takes only the
 * owner's request and the names that memory and the contacts know.
 */
class TaskAllowance(val categories: Set<Category>) {
    /** Reading, typing the request and ordinary settings always; typing screen text when sending is allowed. */
    fun allows(category: Category): Boolean = when (category) {
        Category.READING, Category.TYPING_REQUEST, Category.ORDINARY_SETTINGS -> true
        Category.TYPING_SCREEN_TEXT -> Category.SENDING in categories || category in categories
        else -> category in categories
    }

    /** The task message's sentence about the allowance (design D3). */
    fun describe(): String {
        val parts = listOfNotNull(
            "send messages".takeIf { Category.SENDING in categories },
            "delete things".takeIf { Category.DELETING in categories },
        )
        val calls = if (Category.CALLS in categories) " It may open a call for the owner to place himself." else ""
        if (parts.isEmpty()) return "This task may not send, call or delete anything without asking the owner." + calls
        return "This task may ${parts.joinToString(" and ")} after the owner confirms." + calls
    }

    /**
     * This allowance with [other]'s categories too: the allowance of a conversation's earlier commands (D16 of
     * sub-project 7), or the owner's steering (design 7.3 of sub-project 7).
     */
    operator fun plus(other: TaskAllowance): TaskAllowance = TaskAllowance(categories + other.categories)

    override fun equals(other: Any?): Boolean = other is TaskAllowance && other.categories == categories

    override fun hashCode(): Int = categories.hashCode()

    override fun toString(): String = "TaskAllowance(${categories.sorted().joinToString()})"

    companion object {
        val NONE = TaskAllowance(emptySet())

        private val POSITIONS = setOf("and", "then", "please", "also", "kestrel")
        private val NOT_NAMES = setOf("me", "my", "mi", "meni", "the", "a", "an", "this", "that", "all", "it")
        private val MESSAGE_MARKS = setOf("that", "saying", "about", "da")
        private val DURATIONS = setOf("second", "seconds", "minute", "minutes", "hour", "hours", "sekund", "sekundi", "minut", "minuta", "sat", "sati")
        private val ANY_OBJECT = mapOf(
            "send" to Category.SENDING, "share" to Category.SENDING, "posalji" to Category.SENDING, "odgovori" to Category.SENDING,
            "podeli" to Category.SENDING, "podijeli" to Category.SENDING, "prosledi" to Category.SENDING, "objavi" to Category.SENDING,
            "dial" to Category.CALLS, "ring" to Category.CALLS, "pozovi" to Category.CALLS, "nazovi" to Category.CALLS,
            "delete" to Category.DELETING, "remove" to Category.DELETING, "erase" to Category.DELETING,
            "obrisi" to Category.DELETING, "izbrisi" to Category.DELETING,
        )
        private val NAMED = mapOf(
            "message" to Category.SENDING, "text" to Category.SENDING, "email" to Category.SENDING, "answer" to Category.SENDING,
            "tell" to Category.SENDING, "javi" to Category.SENDING, "call" to Category.CALLS,
        )

        /**
         * The allowance of [request]. A verb counts at the start or after "and", "then", "please", "also" or "Kestrel".
         * "message", "text", "e-mail", "answer", "tell", "javi" and "call" need a name after them that is not "me",
         * "my", "mi", "meni", an article, a determiner or a number, and either a message after it ("that", "saying",
         * "to say", "about", "da", a colon or a quote) or a name of [knownNames]; "forward" and "post" need an object that is
         * no number or length of time; "reply" needs "to"; "clear" needs a noun.
         */
        fun from(request: String, knownNames: Set<String> = emptySet()): TaskAllowance {
            val folded = Normalizer.withOffsets(request)
            val words = folded.words.map { folded.text.substring(it) }
            val known = knownNames.map { Normalizer.words(it) }.filter { it.isNotEmpty() }
            val found = HashSet<Category>()
            for ((index, word) in words.withIndex()) {
                if (index > 0 && words[index - 1] !in POSITIONS) continue
                val rest = words.drop(index + 1)
                ANY_OBJECT[word]?.let { found += it }
                val email = word == "e" && rest.firstOrNull() == "mail"
                val named = if (email) Category.SENDING else NAMED[word]
                if (named != null) {
                    val after = if (email) rest.drop(1) else rest
                    val raw = if (email) folded.span(index + 2, words.size) else folded.span(index + 1, words.size)
                    if (namesSomeone(after, request.substring(raw), known)) found += named
                }
                when (word) {
                    "reply" -> if (rest.firstOrNull() == "to") found += Category.SENDING
                    "forward", "post" -> if (isObject(rest)) found += Category.SENDING
                    "clear" -> if (rest.isNotEmpty() && rest.first().none(Char::isDigit)) found += Category.DELETING
                }
            }
            return TaskAllowance(found)
        }

        private fun namesSomeone(after: List<String>, rawAfter: String, known: List<List<String>>): Boolean {
            val first = after.firstOrNull() ?: return false
            if (first in NOT_NAMES || first.any(Char::isDigit)) return false
            if (known.any { name -> after.size >= name.size && after.subList(0, name.size) == name }) return true
            val message = after.drop(1).take(3)
            return message.any { it in MESSAGE_MARKS } || (message.take(2) == listOf("to", "say")) ||
                ':' in rawAfter || '"' in rawAfter || '\u201C' in rawAfter || '\u201E' in rawAfter
        }

        private fun isObject(rest: List<String>): Boolean {
            val first = rest.firstOrNull() ?: return false
            if (first.any(Char::isDigit) || first in DURATIONS) return false
            return rest.getOrNull(1) !in DURATIONS
        }
    }
}
