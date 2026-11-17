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
        // Tasks 2 to 6 of plan 3C add their rules above this line.
        return null
    }

    private fun MutableMap<String, Command>.phrases(command: Command, vararg phrases: String) {
        for (phrase in phrases) put(phrase, command)
    }

    // Tasks 2 to 6 of plan 3C add their functions above this line.
}
