package dev.kestrel.commands

/** The phone commands of the command list. Every example must parse to a command (`CommandHelpTest`). */
internal object HelpPhone {
    val groups: List<HelpGroup> = listOf(
        HelpGroup(
            "Apps and the system",
            listOf(
                "go back", "go home", "show notifications", "show quick settings", "show recent apps", "show previous app",
                "lock screen", "turn off device", "take a screenshot", "open apps", "circle to search",
            ),
        ),
        HelpGroup("Open apps", listOf("open Chrome", "open work Gmail", "open Gmail in the work profile")),
        HelpGroup("Wi-Fi and Bluetooth", listOf("turn on Wi-Fi", "turn off Bluetooth")),
        HelpGroup(
            "Volume and media",
            listOf("volume up", "turn down the alarm volume", "mute", "unmute the ringer", "silence", "media play", "media pause"),
        ),
        HelpGroup("Magnification", listOf("zoom in", "pan left", "scroll down", "zoom out", "stop magnification")),
        // Tasks 2 to 6 of plan 3C add their groups above this line.
    )
}
