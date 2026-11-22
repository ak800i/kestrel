package dev.kestrel.app.orchestrator

/** What a transcript asks of Kestrel itself (design spec, sections 8.3, 8.5 and 11.10, and Appendix A). */
sealed interface Request {
    /**
     * "Kestrel, [task]". [text] is the task as the owner said it, without the name. [stopFirst]: the owner said "stop" or
     * "cancel" before the task, as in "Stop. Kestrel, open Viber.", so a running task should be cancelled first.
     * [nearWakeUp]: the words are two words away from "wake up", as in "Kestrel, sign up", so that Kestrel starts no task
     * with them while it is paused (plan 4E).
     */
    data class Task(val text: String, val stopFirst: Boolean = false, val nearWakeUp: Boolean = false) : Request {
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

    /**
     * "Kestrel, continue", only as the whole request after the name, with "please" allowed: it ends a hand-over (design D2
     * and D15 of sub-project 5).
     */
    data object Continue : Request

    /**
     * "Kestrel, delete history" (or "delete the history", "delete my history", "clear history"): after a spoken yes,
     * both logs are deleted (design D2 of sub-project 5; spec 13.5).
     */
    data object DeleteHistory : Request

    /**
     * A request about Kestrel's memory, which is read on the phone so that the owner's words go to no provider (design D2,
     * D22 and D23 of sub-project 5). Kestrel asks the owner before it changes anything.
     */
    sealed interface Memory : Request

    /** "Kestrel, remember that [key] is [value]". */
    data class Remember(val key: String, val value: String) : Memory {
        override fun toString() = "Remember(${key.length} and ${value.length} characters)"
    }

    /** "Kestrel, forget [key]". */
    data class Forget(val key: String) : Memory {
        override fun toString() = "Forget(${key.length} characters)"
    }

    /** "Kestrel, save that as [name]": the last finished task becomes a routine (design D23 of sub-project 5). */
    data class SaveRoutine(val name: String) : Memory {
        override fun toString() = "SaveRoutine(${name.length} characters)"
    }

    /** A call command (section 8.7), which [Requests.readCall] reads while a call rings or lasts. */
    sealed interface Call : Request

    /** "Answer", or "answer on speaker" ([speaker]), while a call rings. */
    data class Answer(val speaker: Boolean) : Call

    /** "Decline", while a call rings. */
    data object Decline : Call

    /** "Kestrel, hang up", during a call. */
    data object HangUp : Call

    /** "Kestrel, mute", during a call: the in-call screen's mute control, which stays on when it is on already. */
    data object Mute : Call

    /** "Kestrel, speaker", during a call: the in-call screen's speaker control, which stays on when it is on already. */
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
 * 1. "Stop Kestrel", maybe followed by "please" or "now", turns Kestrel off. When the spotter heard "stop" at the start
 *    of the utterance, the first word may be one letter away from "stop", and the name three letters away (plan 4E).
 * 2. "Stop" or "cancel" with only filler words, such as "No, stop!" or "Cancel the task", is a stop. The name may come
 *    anywhere among them, as in "Stop it, Kestrel"; then the stop is named.
 * 3. The name starts the utterance, or, when the spotter heard it, comes after one short word such as "hey", "okay" or
 *    "stop". Further names right after it are dropped. Then the rest is nothing (the name on its own), a stop,
 *    "wake up", "stop listening", "turn off" or "switch off", "open settings", "open your settings" or "settings",
 *    "continue", "delete history", "remember that <key> is <value>", "forget <key>", "save that as <name>", or a task.
 *    In an answer to "Yes?", "never mind", "forget it" and "nothing" are a named stop, which ends the wait. A task or
 *    "open settings" after "stop" or "cancel" asks to cancel a running task first.
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
 * "Nothing, thanks". After the name, "wake up" also counts with one word misheard, missing or extra, as in "Kestrel, make
 * up" or "Kestrel, wake", where a word counts as misheard when at most half of its letters differ; words two such words
 * away, as in "Kestrel, sign up", are a task that Kestrel does not start while it is paused ([Request.Task.nearWakeUp],
 * plan 4E).
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
    private const val CONTINUE = "continue"
    private val DELETE_HISTORY = setOf("delete history", "delete the history", "delete my history", "clear history")

    // Forms of "forget" that are no fact's name: "Kestrel, forget it" stays a task, as before (Appendix A).
    private val NO_KEYS = setOf("it", "that", "this", "everything", "about it")

    // "Wake up" word by word, for the misheard words of wakeUpDistance().
    private val WAKE_UP = listOf("wake", "up")

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
     * Kestrel" and "Turn off, Kestrel" still turn Kestrel off. [stopSpotted]: the keyword spotter heard "stop" at its
     * start, so that "Stop Kestrel" allows a first word one letter away from "stop" and a name three letters away.
     */
    fun read(transcript: String, spotted: Boolean, afterName: Boolean = false, stopSpotted: Boolean = false): Request? {
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }.toList()
        if (words.isEmpty()) return null
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        if (isStopKestrel(words, limit, stopSpotted)) return Request.TurnOff
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
     * during a call "hang up", "mute" or "speaker". While a call rings, the name may come before or after the command,
     * or not at all; during a call, the command must start with the name, as in "Kestrel, hang up", because the caller's
     * voice can come through the speaker. Kestrel's polite words may come with it, as in "Kestrel, answer, please".
     * Returns null for anything else, which Kestrel ignores during a call. [spotted]: the keyword spotter heard "Kestrel"
     * at the start.
     */
    fun readCall(transcript: String, spotted: Boolean, ringing: Boolean): Request.Call? {
        // The polite words go first, so that they may also follow a trailing name, as in "Decline, Kestrel, please".
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }
            .filter { it.text !in POLITE }
            .toList()
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        // As in read(): when the spotter heard the name, one short word such as "hey" may come before it.
        val start = if (spotted && words.isNotEmpty() && words[0].text in BEFORE_NAME && nameAt(words, 1, limit) > 0) 1 else 0
        val name = nameAt(words, start, limit)
        if (!ringing && name == 0) return null
        val afterName = words.drop(start + name)
        val command = afterName.dropLast(nameBefore(afterName, afterName.size, limit))
        return (if (ringing) RINGING_COMMANDS else CALL_COMMANDS)[phrase(command)]
    }

    private class Word(val text: String, val range: IntRange)

    // "Stop Kestrel" is checked before the name, because the spotter also fires at its start. After the spotter's "stop"
    // at the start, the first word may be one letter away from "stop", and the name as far away as after its "Kestrel".
    private fun isStopKestrel(words: List<Word>, limit: Int, stopSpotted: Boolean): Boolean {
        if (words.size < 2) return false
        val first = words[0].text
        if (first != "stop" && !(stopSpotted && editDistance(first, "stop") <= 1)) return false
        val end = nameAt(words, 1, if (stopSpotted) SPOTTED_LIMIT else limit)
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
        val fromWakeUp = wakeUpDistance(said)
        return when {
            said == "stop listening" -> Request.PauseListening
            nameSaid && said in TURN_OFF -> Request.TurnOff
            said in OPEN_SETTINGS -> Request.OpenSettings(stopFirst)
            said == CONTINUE -> Request.Continue
            said in DELETE_HISTORY -> Request.DeleteHistory
            fromWakeUp <= 1 -> Request.WakeUp
            // "Forget [something]" stays a task, because "Kestrel, forget [fact]" is a command (Appendix A).
            afterName && isNeverMind(rest) -> Request.Stop(named = true)
            else -> memory(transcript, rest)
                ?: Request.Task(transcript.substring(rest.first().range.first).trim(), stopFirst, nearWakeUp = fromWakeUp == 2)
        }
    }

    /**
     * "remember that <key> is <value>", "forget <key>" or "save that as <name>" (design D2 of sub-project 5), with the
     * owner's own words and letter case; any other form, such as "remember to buy milk", is no memory request and runs as
     * a task.
     */
    private fun memory(transcript: String, rest: List<Word>): Request.Memory? {
        val words = rest.map { it.text }
        fun from(index: Int, until: Int = rest.size): String =
            transcript.substring(rest[index].range.first, rest[until - 1].range.last + 1).trim().trimEnd('.', '!', '?', ',').trim()
        return when {
            words.size >= 5 && words[0] == "remember" && words[1] == "that" -> {
                val split = (3 until words.size - 1).firstOrNull { words[it] == "is" } ?: return null
                Request.Remember(from(2, split), from(split + 1))
            }
            words.size >= 2 && words[0] == "forget" && phrase(rest.drop(1)) !in NO_KEYS -> Request.Forget(from(1))
            words.size >= 4 && words[0] == "save" && words[1] in setOf("that", "this") && words[2] == "as" -> Request.SaveRoutine(from(3))
            else -> null
        }
    }

    /**
     * How many words [said] is away from "wake up": a missing, an extra or a misheard word counts 1, and a word counts as
     * misheard when it is at most half its letters away from the word that it replaces, as "make" for "wake" or "op" for
     * "up"; any other word counts as one missing and one extra.
     */
    private fun wakeUpDistance(said: String): Int {
        val words = said.split(" ").filter { it.isNotEmpty() }
        var previous = IntArray(WAKE_UP.size + 1) { it }
        for (word in words) {
            val current = IntArray(WAKE_UP.size + 1)
            current[0] = previous[0] + 1
            for (j in WAKE_UP.indices) {
                val target = WAKE_UP[j]
                val swap = when {
                    word == target -> 0
                    editDistance(word, target) <= target.length / 2 -> 1
                    else -> 2
                }
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + swap)
            }
            previous = current
        }
        return previous[WAKE_UP.size]
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
