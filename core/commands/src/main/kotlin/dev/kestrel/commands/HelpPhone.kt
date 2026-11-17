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
        // Tasks 2 to 6 of plan 3C add their groups above this line.
    )
}
