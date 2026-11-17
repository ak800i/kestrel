package dev.kestrel.commands

/** The screen commands of the command list. Every example must parse to a command (`CommandHelpTest`). */
internal object HelpScreen {
    val groups: List<HelpGroup> = listOf(
        HelpGroup("Tap and press", listOf("tap Send", "tap 5", "long press Send", "press and hold 3")),
        HelpGroup("Switches and sliders", listOf("check Remember me", "uncheck 4", "turn on Dark theme", "increase 6", "decrease 6")),
        HelpGroup("Notifications and lists", listOf("expand 2", "collapse 2", "dismiss 3")),
        HelpGroup(
            "Scroll and swipe",
            listOf("scroll down", "scroll up on 2", "scroll to top", "swipe forwards", "swipe from the left edge", "swipe up"),
        ),
        HelpGroup("Drag and pinch", listOf("drag 3 to 7", "drag 3", "pinch out", "pinch in 4")),
        HelpGroup(
            "Numbers and labels",
            listOf("show numbers", "hide numbers", "show labels", "hide labels", "what is 5", "show commands for 5"),
        ),
        HelpGroup("The grid", listOf("show grid", "more squares", "fewer squares", "tap 12", "hide grid")),
        HelpGroup("Help and repeating", listOf("what can I say", "show all commands", "again")),
    )
}
