package dev.kestrel.app.commands

/** What the screen commands say and show (design spec, sections 9.4 to 9.6). Kestrel speaks only what the owner must act on. */
internal object CommandTexts {
    const val SERVICE_OFF = "My accessibility service is off, so I can't use the screen."
    const val WHICH_ONE = "Which one?"
    const val SCREEN_CHANGED = "The screen changed. Say it again."
    const val SHOW_NUMBERS_FIRST = "Say 'show numbers' first."
    const val NOTHING_HERE = "I can't find anything for that here."

    /** [spoken] is the owner's words as the recogniser wrote them. Kestrel says this text and never logs it. */
    fun cantFind(spoken: String) = "I can't find $spoken."

    fun noNumber(number: Int) = "There's no number $number on the screen."

    /** What "What can I say?" says while the settings app opens on the list of commands (design spec, Appendix A). */
    const val HELP = "You can say things like tap Send, scroll down, go back, show numbers or type hello. " +
        "The list is on the screen."

    const val SHOW_GRID_FIRST = "Say 'show grid' first."

    /** The answer to "What is 5?". [name] is a name on the screen, which Kestrel says and never logs. */
    fun whatIs(number: Int, name: String?, role: String) =
        if (name != null) "Number $number is $name, $role." else "Number $number has no name. It's $role."

    /** The answer to "What is 5?" while the grid shows. */
    fun square(number: Int) = "That's square $number of the grid."

    /** The answer to "Show commands for X", such as "You can say tap, check or uncheck." */
    fun youCanSay(phrases: List<String>) = when (phrases.size) {
        0 -> "There's nothing to say for it."
        1 -> "You can say ${phrases.single()}."
        else -> "You can say ${phrases.dropLast(1).joinToString(", ")} or ${phrases.last()}."
    }

    const val NOTHING_SWIPES_SIDEWAYS = "Nothing on the screen swipes sideways."
    const val DRAG_WHERE = "Drag it where?"

    const val ALREADY_ON = "It's already on."
    const val ALREADY_OFF = "It's already off."
    const val ALREADY_EXPANDED = "It's already expanded."
    const val ALREADY_COLLAPSED = "It's already collapsed."
    const val CANT_EXPAND = "It can't expand."
    const val CANT_COLLAPSE = "It can't collapse."
    const val NOT_CHECKABLE = "It isn't a check box or a switch."
    const val CANT_UNCHECK_RADIO = "A radio button turns off only when you check another one."

    /** An answer to "Which one?" that names a number with another verb, such as "long press 2" (design spec, section 9.4). */
    const val SAY_ONLY_THE_NUMBER = "Say only the number."

    /** A number that the owner began to say before the numbers on the screen or the grid changed (section 9.4). */
    const val NUMBERS_CHANGED = "The numbers changed. Say it again."
}
