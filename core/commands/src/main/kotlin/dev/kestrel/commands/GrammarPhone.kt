package dev.kestrel.commands

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
        // Tasks 2 to 6 of plan 3C add their phrases above this line.
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        openApp(words, text)?.let { return it }
        // Tasks 2 to 6 of plan 3C add their rules above this line.
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

    // "Open Chrome", "open work Gmail", "open Gmail in the work profile". The name keeps the recogniser's case.
    private fun openApp(words: List<String>, text: String): Command? {
        val end = withoutPlease(words)
        if (end < 2 || words[0] !in OPEN_VERBS) return null
        if (words[0] == "open" && words[1] in PUNCTUATION) return null
        if (words[1] == "work" && end > 2) return PhoneCommand.OpenApp(cutWords(text, 2, end), work = true)
        val ending = IN_WORK_PROFILE.firstOrNull { end - 1 > it.size && words.subList(end - it.size, end) == it }
        if (ending != null) return PhoneCommand.OpenApp(cutWords(text, 1, end - ending.size), work = true)
        return PhoneCommand.OpenApp(cutWords(text, 1, end), work = false)
    }

    // Tasks 2 to 6 of plan 3C add their functions above this line.
}
