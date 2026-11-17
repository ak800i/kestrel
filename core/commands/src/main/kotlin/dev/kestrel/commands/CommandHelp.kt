package dev.kestrel.commands

/** A group of the command list that Kestrel's settings app shows (design spec, Appendix A, help). */
data class HelpGroup(val title: String, val examples: List<String>)

/** Every group of the command list: plan 3A's screen commands, then plan 3B's text and plan 3C's phone commands. */
object CommandHelp {
    val groups: List<HelpGroup> get() = HelpScreen.groups + HelpText.groups + HelpPhone.groups
}
