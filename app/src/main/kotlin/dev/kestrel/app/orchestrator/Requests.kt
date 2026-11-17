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

    /** "Stop Kestrel", or "Kestrel, turn off": off. */
    data object TurnOff : Request

    /**
     * "Kestrel, open settings" or "Kestrel settings": Kestrel's settings app (section 15). [stopFirst]: the owner said
     * "stop" or "cancel" before it, as in "Stop. Kestrel, open settings.", so a running task should be cancelled first.
     */
    data class OpenSettings(val stopFirst: Boolean = false) : Request

    /** A call command (section 8.7), which [Requests.readCall] reads while a call rings or lasts. */
    sealed interface Call : Request

    /** "Answer", or "answer on speaker" ([speaker]), while a call rings. */
    data class Answer(val speaker: Boolean) : Call

    /** "Decline", while a call rings. */
    data object Decline : Call

    /** "Hang up", during a call. */
    data object HangUp : Call

    /** "Mute", during a call: the in-call screen's mute control. */
    data object Mute : Call

    /** "Speaker", during a call: the in-call screen's speaker control. */
    data object Speaker : Call

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
 *    "wake up", "stop listening", "turn off" or "switch off", "open settings", "open your settings" or "settings", or a
 *    task. In an answer to "Yes?", "never mind", "forget it" and "nothing" are a named stop, which ends the wait. A task
 *    or "open settings" after "stop" or "cancel" asks to cancel a running task first.
 * 4. Otherwise "stop listening" pauses, and "wake up", "stop listening", "turn off" or "switch off" followed by the
 *    name count too. In an answer to "Yes?", "never mind", "forget it" and "nothing" followed by the name are a named
 *    stop.
 *    Everything else is for the instant commands, or, in an answer to "Yes?", read as the words after the name. A bare
 *    "turn off" or "switch off" in an answer is a task, though: the answer is held for 1.5 s of silence (section 8.3),
 *    but a longer pause still splits it, so the owner may still be saying what to turn off, and "Turn off ... the
 *    wifi" would otherwise turn Kestrel off. "Kestrel, turn off", "Turn off, Kestrel" and "Stop Kestrel" still turn
 *    Kestrel off.
 *
 * Kestrel's own phrases are compared without "please", "now", "ok" and "okay", and "wakeup" reads as "wake up". The
 * answers that end the wait may also come with "no", "oh", "ah", "thanks" or "thank you", as in "No, never mind" or
 * "Nothing, thanks".
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

    // Kestrel's own phrases after the name, as phrase() gives them (Appendix A).
    private val TURN_OFF = setOf("turn off", "switch off")
    private val OPEN_SETTINGS = setOf("open settings", "open your settings", "settings")

    // Answers to "Yes?" that end the wait (section 8.3).
    private val NEVER_MIND = setOf("never mind", "nevermind", "forget it", "nothing")

    // Words that may come before or after those answers, as in "No, never mind" or "Nothing, thank you".
    private val AROUND_NEVER_MIND =
        listOf(listOf("no"), listOf("oh"), listOf("ah"), listOf("thanks"), listOf("thank", "you"))

    // The call commands, as phrase() gives them (section 8.7): while a call rings, and during the call.
    private val RINGING_COMMANDS: Map<String, Request.Call> = mapOf(
        "answer" to Request.Answer(speaker = false),
        "answer on speaker" to Request.Answer(speaker = true),
        "answer on the speaker" to Request.Answer(speaker = true),
        "decline" to Request.Decline,
    )
    private val CALL_COMMANDS: Map<String, Request.Call> = mapOf(
        "hang up" to Request.HangUp,
        "mute" to Request.Mute,
        "speaker" to Request.Speaker,
    )

    /**
     * Returns null when [transcript] has no words. [spotted]: the keyword spotter heard "Kestrel" at its start.
     * [afterName]: the transcript answers "Yes?", so words without the name are read as the words after it: "Wake up."
     * is [Request.WakeUp], "Stop." is a named stop, and "Open the settings." is a task. "Turn off." is a task too,
     * because a pause of more than 1.5 s still splits the answer and the owner may not have finished, while "Stop
     * Kestrel" and "Turn off, Kestrel" still turn Kestrel off.
     */
    fun read(transcript: String, spotted: Boolean, afterName: Boolean = false): Request? {
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }.toList()
        if (words.isEmpty()) return null
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        if (isStopKestrel(words, limit)) return Request.TurnOff
        readStop(words, limit)?.let { return if (afterName) Request.Stop(named = true) else it }
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
            val stopFirst = start == 1 && words[0].text in STOP_WORDS
            return named(transcript, rest, limit, stopFirst = stopFirst, afterName = afterName, nameSaid = true)
        }
        val phrase = phrase(words)
        val trailingName = nameBefore(words, words.size, limit)
        val beforeName = if (trailingName > 0) phrase(words.dropLast(trailingName)) else null
        return when {
            phrase == "stop listening" || beforeName == "stop listening" -> Request.PauseListening
            beforeName == "wake up" -> Request.WakeUp
            beforeName != null && beforeName in TURN_OFF -> Request.TurnOff
            afterName && trailingName > 0 && isNeverMind(words.dropLast(trailingName)) -> Request.Stop(named = true)
            afterName -> named(transcript, words, limit, stopFirst = false, afterName = true, nameSaid = false)
            else -> Request.Other(transcript.trim())
        }
    }

    /**
     * Reads a call command (section 8.7): while a call rings ([ringing]) "answer", "answer on speaker" or "decline", and
     * during a call "hang up", "mute" or "speaker". The name may come before or after the command, and Kestrel's polite
     * words may come with it, as in "Kestrel, answer, please". Returns null for anything else, which Kestrel ignores
     * during a call. [spotted]: the keyword spotter heard "Kestrel" at the start.
     */
    fun readCall(transcript: String, spotted: Boolean, ringing: Boolean): Request.Call? {
        // The polite words go first, so that they may also follow a trailing name, as in "Decline, Kestrel, please".
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }
            .filter { it.text !in POLITE }
            .toList()
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        // As in read(): when the spotter heard the name, one short word such as "hey" may come before it.
        val start = if (spotted && words.isNotEmpty() && words[0].text in BEFORE_NAME && nameAt(words, 1, limit) > 0) 1 else 0
        val afterName = words.drop(start + nameAt(words, start, limit))
        val command = afterName.dropLast(nameBefore(afterName, afterName.size, limit))
        return (if (ringing) RINGING_COMMANDS else CALL_COMMANDS)[phrase(command)]
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

    /**
     * What [rest], the words after the name, ask for. [afterName]: they answer "Yes?". [nameSaid]: the owner said the
     * name before them; without it, a bare "turn off" is a task, because the answer is held for only 1.5 s of silence,
     * and after a longer pause the owner may not have finished.
     */
    private fun named(
        transcript: String,
        rest: List<Word>,
        limit: Int,
        stopFirst: Boolean,
        afterName: Boolean,
        nameSaid: Boolean,
    ): Request {
        if (rest.isEmpty()) return Request.Name
        if (readStop(rest, limit) != null) return Request.Stop(named = true)
        val said = phrase(rest)
        return when {
            said == "wake up" -> Request.WakeUp
            said == "stop listening" -> Request.PauseListening
            nameSaid && said in TURN_OFF -> Request.TurnOff
            said in OPEN_SETTINGS -> Request.OpenSettings(stopFirst)
            // "Forget [something]" stays a task, because "Kestrel, forget [fact]" is a command (Appendix A).
            afterName && isNeverMind(rest) -> Request.Stop(named = true)
            else -> Request.Task(transcript.substring(rest.first().range.first).trim(), stopFirst)
        }
    }

    /** [words] without the polite words, with "wakeup" read as "wake up". */
    private fun phrase(words: List<Word>): String =
        words.filter { it.text !in POLITE }.joinToString(" ") { if (it.text == "wakeup") "wake up" else it.text }

    /** Whether [words] are one of [NEVER_MIND], maybe with the words of [AROUND_NEVER_MIND] before or after it. */
    private fun isNeverMind(words: List<Word>): Boolean {
        var said = phrase(words).split(" ")
        while (true) {
            said = AROUND_NEVER_MIND.firstNotNullOfOrNull { extra ->
                when {
                    said.size <= extra.size -> null
                    said.take(extra.size) == extra -> said.drop(extra.size)
                    said.takeLast(extra.size) == extra -> said.dropLast(extra.size)
                    else -> null
                }
            } ?: break
        }
        return said.joinToString(" ") in NEVER_MIND
    }

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
