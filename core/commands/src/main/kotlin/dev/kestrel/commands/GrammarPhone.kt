package dev.kestrel.commands

import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.commands.text.Normalizer

/**
 * The phone phrases of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A): the global actions, apps, the phone's
 * settings, media, magnification and calls. Its targeted rules return null for a target that is not theirs: "turn on" and
 * "switch on" only for Wi-Fi and Bluetooth, "increase" and "turn up" only with "volume", "open X" never for the spoken
 * punctuation of dictation ("open quote", "open parenthesis", "open bracket"), "call X" and "dial X".
 */
internal object GrammarPhone {
    private val FIXED: Map<String, Command> = buildMap {
        phrases(PhoneCommand.Global(GlobalCommand.BACK), "back", "go back", "navigate back")
        phrases(PhoneCommand.Global(GlobalCommand.HOME), "home", "go home", "go to home", "home screen", "go to the home screen")
        phrases(
            PhoneCommand.Global(GlobalCommand.NOTIFICATIONS),
            "show notifications", "show the notifications", "show my notifications", "open notifications",
            "open the notifications", "open the notification shade",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.QUICK_SETTINGS),
            "show quick settings", "show the quick settings", "open quick settings", "open the quick settings", "quick settings",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.RECENT_APPS),
            "show recent apps", "show the recent apps", "open recent apps", "recent apps", "show recents", "recents",
        )
        phrases(PhoneCommand.Global(GlobalCommand.LOCK_SCREEN), "lock screen", "lock the screen", "lock the phone", "lock phone", "lock my phone")
        phrases(
            PhoneCommand.Global(GlobalCommand.POWER_MENU),
            "turn off device", "turn off the device", "power menu", "show power menu", "show the power menu", "open the power menu",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.SCREENSHOT),
            "take screenshot", "take a screenshot", "screenshot", "take a screen shot", "capture the screen",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.ALL_APPS),
            "open apps", "open all apps", "show apps", "show all apps", "all apps", "open the app drawer", "show the app drawer",
        )
        phrases(
            PhoneCommand.PreviousApp,
            "show previous app", "show the previous app", "previous app", "go to previous app", "go to the previous app",
            "switch to the previous app", "switch apps",
        )
        phrases(PhoneCommand.CircleToSearch, "circle to search", "start circle to search", "open circle to search")
        phrases(
            PhoneCommand.Silence,
            "silence", "silence the phone", "silence my phone", "silent mode", "vibrate", "vibrate mode", "vibrate only",
            "set to vibrate", "turn on vibrate",
        )
        // Back from vibrate to sound, as "unmute the ringer" does.
        phrases(PhoneCommand.Mute(StreamKind.RING, on = false), "turn off vibrate", "turn vibrate off", "vibrate off")
        phrases(PhoneCommand.Media(play = true), "media play", "play media", "play the music", "play music", "resume media", "resume the music")
        phrases(PhoneCommand.Media(play = false), "media pause", "pause media", "pause the music", "pause music")
        phrases(
            PhoneCommand.Magnify(MagnifyAction.IN),
            "start magnification", "start zooming", "magnify", "magnify the screen", "zoom in", "enhance", "magnification on",
        )
        phrases(PhoneCommand.Magnify(MagnifyAction.OUT), "zoom out")
        phrases(
            PhoneCommand.Magnify(MagnifyAction.STOP),
            "stop magnification", "stop zooming", "cancel zooming", "stop magnifying", "end magnification", "magnification off",
        )
        phrases(PhoneCommand.Pan(Direction.UP), "pan up", "move up", "go up")
        phrases(PhoneCommand.Pan(Direction.DOWN), "pan down", "move down", "go down")
        phrases(PhoneCommand.Pan(Direction.LEFT), "pan left", "move left", "go left")
        phrases(PhoneCommand.Pan(Direction.RIGHT), "pan right", "move right", "go right")
        phrases(
            PhoneCommand.CopyPhoneNumber,
            "copy this phone number", "copy the phone number", "copy phone number", "copy this number", "copy the number",
        )
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        openApp(words, text)?.let { return it }
        radio(words)?.let { return it }
        volume(words)?.let { return it }
        mute(words)?.let { return it }
        call(words, text)?.let { return it }
        return null
    }

    private fun MutableMap<String, Command>.phrases(command: Command, vararg phrases: String) {
        for (phrase in phrases) put(phrase, command)
    }

    private val OPEN_VERBS = setOf("open", "launch")

    // A name never ends with "please", which the parser removes only from a bare text.
    private fun withoutPlease(words: List<String>): Int = if (words.size > 2 && words.last() == "please") words.size - 1 else words.size

    // Plan 3B's spoken punctuation, which "open" never opens as an app: "open quote", "open parenthesis" and the others.
    private val PUNCTUATION = setOf("quote", "quotes", "double", "single", "parenthesis", "parentheses", "paren", "bracket", "brackets")
    private val IN_WORK_PROFILE = listOf(
        listOf("in", "the", "work", "profile"),
        listOf("in", "work", "profile"),
        listOf("from", "the", "work", "profile"),
    )

    // "Open Chrome", "open work Gmail", "open Gmail in the work profile", also both at once. The name keeps the recogniser's
    // case.
    private fun openApp(words: List<String>, text: String): Command? {
        val end = withoutPlease(words)
        if (end < 2 || words[0] !in OPEN_VERBS) return null
        if (words[0] == "open" && words[1] in PUNCTUATION) return null
        val start = if (words[1] == "work" && end > 2) 2 else 1
        val ending = IN_WORK_PROFILE.firstOrNull { end - start > it.size && words.subList(end - it.size, end) == it }
        if (start == 2 || ending != null) return PhoneCommand.OpenApp(cutWords(text, start, end - (ending?.size ?: 0)), work = true)
        return PhoneCommand.OpenApp(cutWords(text, 1, end), work = false)
    }

    private val SWITCH_VERBS = setOf("turn", "switch")
    private val ON_OFF = mapOf("on" to true, "off" to false)
    private val ENABLE = mapOf("enable" to true, "disable" to false)

    // "Turn on Wi-Fi", "turn Bluetooth off", "enable Bluetooth", "Wi-Fi off", also with "please" or "now" after them.
    // Normalizer writes "Wi-Fi" as "wi fi", and the recogniser may write "Bluetooth" as "blue tooth".
    private fun radio(said: List<String>): Command? {
        val words = said.dropLastWhile { it in CommandParser.POLITE_WORDS }
        val (on, rest) = when {
            words.size >= 3 && words[0] in SWITCH_VERBS && words[1] in ON_OFF -> ON_OFF.getValue(words[1]) to words.drop(2)
            words.size >= 3 && words[0] in SWITCH_VERBS && words.last() in ON_OFF ->
                ON_OFF.getValue(words.last()) to words.subList(1, words.size - 1)
            words.size >= 2 && words[0] in ENABLE -> ENABLE.getValue(words[0]) to words.drop(1)
            words.size >= 2 && words.last() in ON_OFF -> ON_OFF.getValue(words.last()) to words.dropLast(1)
            else -> return null
        }
        val radio = when (rest.dropWhile { it == "the" || it == "my" }) {
            listOf("wi", "fi"), listOf("wifi") -> RadioKind.WIFI
            listOf("bluetooth"), listOf("blue", "tooth") -> RadioKind.BLUETOOTH
            else -> return null
        }
        return PhoneCommand.Radio(radio, on)
    }

    private val STREAMS = mapOf(
        "media" to StreamKind.MEDIA, "music" to StreamKind.MEDIA, "alarm" to StreamKind.ALARM, "alarms" to StreamKind.ALARM,
        "phone" to StreamKind.CALL, "call" to StreamKind.CALL, "ring" to StreamKind.RING, "ringer" to StreamKind.RING,
        "ringtone" to StreamKind.RING, "notification" to StreamKind.NOTIFICATION, "notifications" to StreamKind.NOTIFICATION,
    )
    private val UP = setOf("up", "increase", "raise", "louder")
    private val DOWN = setOf("down", "decrease", "lower", "reduce", "quieter")
    private val VOLUME_WORDS = setOf("turn", "the", "my", "volume", "of", "a", "bit", "little")

    // "Volume up", "turn the alarm volume down", "increase the ringer volume": "volume", one direction, at most one stream
    // and no other words, also with "please" or "now" after them.
    private fun volume(said: List<String>): Command? {
        val words = said.dropLastWhile { it in CommandParser.POLITE_WORDS }
        if ("volume" !in words) return null
        var up: Boolean? = null
        var stream: StreamKind? = null
        for (word in words) {
            when (word) {
                in UP -> if (up == false) return null else up = true
                in DOWN -> if (up == true) return null else up = false
                in STREAMS -> stream = oneStream(stream, STREAMS.getValue(word)) ?: return null
                in VOLUME_WORDS -> Unit
                else -> return null
            }
        }
        return up?.let { PhoneCommand.Volume(stream, it) }
    }

    private val MUTE_WORDS = setOf("the", "my", "volume", "sound", "audio")

    // "Mute the phone" means its ringer, not the call volume.
    private val MUTE_STREAMS = STREAMS + ("phone" to StreamKind.RING)

    // "Mute", "unmute the ringer", "mute the alarm volume", also with "please" or "now" after them.
    private fun mute(said: List<String>): Command? {
        val words = said.dropLastWhile { it in CommandParser.POLITE_WORDS }
        val (on, from) = when {
            words.firstOrNull() == "mute" -> true to 1
            words.firstOrNull() == "unmute" -> false to 1
            words.size >= 2 && words[0] == "un" && words[1] == "mute" -> false to 2
            else -> return null
        }
        var stream: StreamKind? = null
        for (word in words.drop(from)) {
            when (word) {
                in MUTE_STREAMS -> stream = oneStream(stream, MUTE_STREAMS.getValue(word)) ?: return null
                in MUTE_WORDS -> Unit
                else -> return null
            }
        }
        return PhoneCommand.Mute(stream, on)
    }

    // Two different streams in one phrase name none.
    private fun oneStream(current: StreamKind?, named: StreamKind): StreamKind? = if (current == null || current == named) named else null

    private val CALL_VERBS = setOf("call", "dial")
    private val KINDS = setOf("mobile", "cell", "work", "home", "main")
    private val NUMBER_WORDS = setOf("number", "phone")
    private val PLACE_WORDS = setOf("on", "at")
    private val POSSESSIVE = Regex("['\u2019]s$", RegexOption.IGNORE_CASE)

    // "Call 064 123 4567", "call Ana", "call Ana mobile", "call Ana on work", "call Ana's work number", "call Ana's gym
    // number". The kind is a known kind after the name, or the words after a name with "'s". A name or a number never
    // ends with a polite word, which the parser removes only from a bare text: "call Ana now".
    private fun call(words: List<String>, text: String): Command? {
        var said = words.size
        while (said > 1 && words[said - 1] in CommandParser.POLITE_WORDS) said--
        if (said < 2 || words[0] !in CALL_VERBS) return null
        PhoneNumbers.digits(numberText(text, said))?.let { return PhoneCommand.Call(CallTarget.Number(it)) }
        val end = if (said > 2 && words[said - 1] in NUMBER_WORDS) said - 1 else said
        val owner = (1 until end).firstOrNull { POSSESSIVE.containsMatchIn(cutWords(text, it, it + 1)) }
        if (owner != null) {
            val name = cutWords(text, 1, owner + 1).replace(POSSESSIVE, "")
            val kind = words.subList(owner + 1, end).joinToString(" ").ifEmpty { null }
            return PhoneCommand.Call(CallTarget.Name(name, kind))
        }
        if (end > 2 && words[end - 1] in KINDS) {
            var nameEnd = end - 1
            if (nameEnd > 2 && words[nameEnd - 1] in PLACE_WORDS) nameEnd--
            return PhoneCommand.Call(CallTarget.Name(cutWords(text, 1, nameEnd), words[end - 1]))
        }
        return PhoneCommand.Call(CallTarget.Name(cutWords(text, 1, said), null))
    }

    private const val NUMBER_SIGNS = "+*# "

    // The words after the verb with the "+", "*" and "#" around them, which Normalizer drops as punctuation.
    private fun numberText(text: String, end: Int): String {
        val folded = Normalizer.withOffsets(text)
        val span = folded.span(1, end)
        val verbEnd = folded.span(0, 1).last + 1
        var start = span.first
        while (start > verbEnd && text[start - 1] in NUMBER_SIGNS) start--
        var stop = span.last + 1
        while (stop < text.length && text[stop] in NUMBER_SIGNS) stop++
        return text.substring(start, stop)
    }
}
