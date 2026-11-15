package dev.kestrel.app.orchestrator

/** What a transcript asks of Kestrel itself (design spec, sections 8.3, 8.5 and 11.10, and Appendix A). */
sealed interface Request {
    /** "Kestrel, [task]". [text] is the task as the owner said it, without the name. */
    data class Task(val text: String) : Request {
        override fun toString() = "Task(${text.length} characters)"
    }

    /** "Kestrel" on its own: Kestrel answers "Yes?" and takes the next utterance as the task. */
    data object Name : Request

    /** "Kestrel, wake up": back from the paused mode to listening. */
    data object WakeUp : Request

    /** "Stop" or "cancel"; [named] when the owner said "Kestrel" first. */
    data class Stop(val named: Boolean) : Request

    /** "Stop listening": the paused mode. */
    data object PauseListening : Request

    /** "Stop Kestrel": off. */
    data object TurnOff : Request

    /** Anything else, which the instant commands of sub-project 3 will read. */
    data class Other(val text: String) : Request {
        override fun toString() = "Other(${text.length} characters)"
    }
}

/**
 * Reads what a transcript asks of Kestrel (design spec, section 8.3). The recogniser may misspell the name, for example
 * as "Kestral", "Castrel" or "Kes trel", so a word counts as the name when it has at least five letters, starts with "k"
 * or "c" and is at most two letters away from "kestrel". When the keyword spotter heard the name at the start of the
 * utterance, three letters are allowed, and the name may also be the second word, as in "Hey Kestrel".
 */
object Requests {
    private const val NAME = "kestrel"
    private const val HEARD_LIMIT = 2
    private const val SPOTTED_LIMIT = 3
    private val WORD = Regex("""[\p{L}\p{N}']+""")
    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come with "stop" or "cancel", as in "Okay, stop it".
    private val FILLERS = setOf("it", "that", "this", "please", "now", "ok", "okay")

    /** Returns null when [transcript] has no words. [spotted]: the keyword spotter heard "Kestrel" at its start. */
    fun read(transcript: String, spotted: Boolean): Request? {
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }.toList()
        if (words.isEmpty()) return null
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        // Before the name: the spotter also fires at the start of "Stop Kestrel".
        if (words.size in 2..3 && words[0].text == "stop" && nameEnd(words.drop(1), limit, spotted = false) == words.size - 1) {
            return Request.TurnOff
        }
        val name = nameEnd(words, limit, spotted)
        if (name > 0) return named(transcript, words.drop(name))
        return when {
            isStop(words) -> Request.Stop(named = false)
            words.joinToString(" ") { it.text } == "stop listening" -> Request.PauseListening
            else -> Request.Other(transcript.trim())
        }
    }

    private class Word(val text: String, val range: IntRange)

    /** What [rest], the words after the name, ask for. */
    private fun named(transcript: String, rest: List<Word>): Request = when {
        rest.isEmpty() -> Request.Name
        isStop(rest) -> Request.Stop(named = true)
        else -> when (rest.joinToString(" ") { it.text }) {
            "wake up" -> Request.WakeUp
            "stop listening" -> Request.PauseListening
            else -> Request.Task(transcript.substring(rest.first().range.first).trim())
        }
    }

    private fun isStop(words: List<Word>): Boolean =
        words.any { it.text in STOP_WORDS } && words.all { it.text in STOP_WORDS || it.text in FILLERS }

    /**
     * How many of the first words end with the name, or 0. The name is one word or two that make it together, and it is
     * the first word, or, when the spotter heard it, also the second.
     */
    private fun nameEnd(words: List<Word>, limit: Int, spotted: Boolean): Int {
        for (first in 0..(if (spotted) 1 else 0)) {
            if (first >= words.size) break
            if (isName(words[first].text, limit)) return first + 1
            if (first + 1 < words.size && isName(words[first].text + words[first + 1].text, limit)) return first + 2
        }
        return 0
    }

    private fun isName(word: String, limit: Int): Boolean =
        word.length >= 5 && word[0] in "kc" && editDistance(word, NAME) <= limit

    /** The Levenshtein distance: how many letters must be added, removed or replaced to turn [a] into [b]. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
