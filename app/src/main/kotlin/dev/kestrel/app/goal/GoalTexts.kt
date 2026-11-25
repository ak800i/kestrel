package dev.kestrel.app.goal

/**
 * The goal notification's own words (design 10.6 of sub-project 7; main spec, section 15), in English and built by code.
 * A held goal's question and how to go on are the conversation's (`ConversationTexts`).
 */
internal object GoalTexts {
    /** The notification channel's name in Android's settings. */
    const val CHANNEL = "Goals"
    const val WORKING = "Kestrel is working"

    /**
     * In place of the current item while the goal's round waits behind the owner's instant command, whose own text the bar
     * shows then (plan 7E).
     */
    const val BEHIND_COMMAND = "Waiting for your command to finish"

    /** After the goal's end, while its final reply is spoken or shown, or waits for the owner's return (design 6.4). */
    const val FINISHED = "Kestrel finished the goal"

    /** The lock screen's version: the current checklist item may hold names. */
    const val PUBLIC = "Kestrel is working on a task"
    const val STOP = "Stop"
    const val OPEN = "Open"
    const val CONTINUE = "Continue"
    const val CANCEL = "Cancel"
}
