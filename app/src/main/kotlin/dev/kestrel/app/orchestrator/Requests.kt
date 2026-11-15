package dev.kestrel.app.orchestrator

/** What a transcript asks of Kestrel itself (design spec, sections 8.3, 8.5 and 11.10, and Appendix A). */
sealed interface Request {
    /**
     * "Kestrel, [task]". [text] is the task as the owner said it, without the name. [stopFirst]: the owner said "stop" or
     * "cancel" before the task, as in "Stop. Kestrel, open Viber.", so a running task should be cancelled first.
     */
    data class Task(val text: String, val stopFirst: Boolean = false) : Request {
        override fun toString() = "Task(${text.length} characters)"
    }

    /** "Kestrel" on its own: Kestrel answers "Yes?" and takes the next utterance as the task. */
    data object Name : Request

    /** "Kestrel, wake up": back from the paused mode to listening. */
    data object WakeUp : Request

    /** "Stop" or "cancel"; [named] when the owner also said "Kestrel", as in "Kestrel, stop" or "Stop it, Kestrel". */
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
 * as "Kestral", "Castrel" or "Kes trel", so a word, or two words together, count as the name when they have at least
 * five letters, start with "k" or "c" and are at most two letters away from "kestrel". When the keyword spotter heard
 * the name at the start of the utterance, three letters are allowed.
 *
 * The checks run in this order:
 * 1. "Stop Kestrel", maybe followed by "please" or "now", turns Kestrel off.
 * 2. "Stop" or "cancel" with only filler words, such as "No, stop!" or "Cancel the task", is a stop. The name may come
 *    anywhere among them, as in "Stop it, Kestrel"; then the stop is named.
 * 3. The name starts the utterance, or, when the spotter heard it, comes after one short word such as "hey", "okay" or
 *    "stop". Further names right after it are dropped. Then the rest is nothing (the name on its own), a stop,
 *    "wake up", "stop listening", or a task. A task after "stop" or "cancel" asks to cancel a running task first.
 * 4. Otherwise "stop listening" pauses, and "wake up" or "stop listening" followed by the name count too.
 *    Everything else is for the instant commands.
 *
 * Kestrel's own phrases are compared without "please", "now", "ok" and "okay", and "wakeup" reads as "wake up".
 */
object Requests {
    private const val NAME = "kestrel"
    private const val HEARD_LIMIT = 2
    private const val SPOTTED_LIMIT = 3
    private val WORD = Regex("""[\p{L}\p{N}]+(?:'[\p{L}\p{N}]+)*""")
    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come with "stop" or "cancel", as in "Okay, stop it" or "No, cancel the task".
    private val FILLERS = setOf(
        "it", "that", "this", "please", "now", "ok", "okay", "no", "wait", "hey", "oh", "just", "right", "all",
        "everything", "the", "task",
    )

    // The words that may come before the name when the spotter heard it, as in "Hey Kestrel" or "Stop. Kestrel, ...".
    private val BEFORE_NAME = setOf("hey", "hi", "hello", "ok", "okay", "oh", "so", "um", "uh", "well", "yo") + STOP_WORDS

    // Words that Kestrel's own phrases may come with, as in "Kestrel, wake up, please".
    private val POLITE = setOf("please", "now", "ok", "okay")

    /** Returns null when [transcript] has no words. [spotted]: the keyword spotter heard "Kestrel" at its start. */
    fun read(transcript: String, spotted: Boolean): Request? {
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }.toList()
        if (words.isEmpty()) return null
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        if (isStopKestrel(words, limit)) return Request.TurnOff
        readStop(words, limit)?.let { return it }
        val start = when {
            nameAt(words, 0, limit) > 0 -> 0
            spotted && words[0].text in BEFORE_NAME && nameAt(words, 1, limit) > 0 -> 1
            else -> -1
        }
        if (start >= 0) {
            var rest = words.drop(start + nameAt(words, start, limit))
            var again = nameAt(rest, 0, limit)
            while (again > 0) {
                rest = rest.drop(again)
                again = nameAt(rest, 0, limit)
            }
            return named(transcript, rest, limit, stopFirst = start == 1 && words[0].text in STOP_WORDS)
        }
        val phrase = phrase(words)
        val trailingName = nameBefore(words, words.size, limit)
        val beforeName = if (trailingName > 0) phrase(words.dropLast(trailingName)) else null
        return when {
            phrase == "stop listening" || beforeName == "stop listening" -> Request.PauseListening
            beforeName == "wake up" -> Request.WakeUp
            else -> Request.Other(transcript.trim())
        }
    }

    private class Word(val text: String, val range: IntRange)

    // "Stop Kestrel" is checked before the name, because the spotter also fires at its start.
    private fun isStopKestrel(words: List<Word>, limit: Int): Boolean {
        if (words.size < 2 || words[0].text != "stop") return false
        val end = nameAt(words, 1, limit)
        return end > 0 && words.drop(1 + end).all { it.text == "please" || it.text == "now" }
    }

    /** A stop when [words] are "stop" or "cancel" with only fillers and names; it is named when a name is among them. */
    private fun readStop(words: List<Word>, limit: Int): Request.Stop? {
        if (words.none { it.text in STOP_WORDS }) return null
        var named = false
        var i = 0
        while (i < words.size) {
            if (words[i].text in STOP_WORDS || words[i].text in FILLERS) {
                i++
                continue
            }
            val name = nameAt(words, i, limit)
            if (name == 0) return null
            named = true
            i += name
        }
        return Request.Stop(named)
    }

    /** What [rest], the words after the name, ask for. */
    private fun named(transcript: String, rest: List<Word>, limit: Int, stopFirst: Boolean): Request {
        if (rest.isEmpty()) return Request.Name
        if (readStop(rest, limit) != null) return Request.Stop(named = true)
        return when (phrase(rest)) {
            "wake up" -> Request.WakeUp
            "stop listening" -> Request.PauseListening
            else -> Request.Task(transcript.substring(rest.first().range.first).trim(), stopFirst)
        }
    }

    /** [words] without the polite words, with "wakeup" read as "wake up". */
    private fun phrase(words: List<Word>): String =
        words.filter { it.text !in POLITE }.joinToString(" ") { if (it.text == "wakeup") "wake up" else it.text }

    /** How many words, one or two, make the name from [start], or 0. */
    private fun nameAt(words: List<Word>, start: Int, limit: Int): Int = when {
        start >= words.size -> 0
        isName(words[start].text, limit) -> 1
        start + 1 < words.size && isName(words[start].text + words[start + 1].text, limit) -> 2
        else -> 0
    }

    /** How many words, one or two, make the name that ends just before [end], or 0. */
    private fun nameBefore(words: List<Word>, end: Int, limit: Int): Int = when {
        end >= 1 && isName(words[end - 1].text, limit) -> 1
        end >= 2 && isName(words[end - 2].text + words[end - 1].text, limit) -> 2
        else -> 0
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
