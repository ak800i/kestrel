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
}
