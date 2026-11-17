package dev.kestrel.app.commands

/**
 * What the phone commands say and show (design spec, sections 9.3 and 13.6, and Appendix A). Kestrel speaks only what the
 * owner must act on; the other texts go to the feedback bar. A text with a name or a number in it is never logged.
 */
internal object PhoneCommandTexts {
    const val NO_APP_DRAWER = "I can't open the app drawer on this phone."
    const val NOT_SWITCHED = "I couldn't switch apps."
    const val NO_CIRCLE_TO_SEARCH = "Circle to Search isn't available on this phone."

    /** The system action of Circle to Search, where the launcher offers it (research r25). */
    const val CIRCLE_TO_SEARCH_ACTION = 500

    // Tasks 2 to 6 of plan 3C add their texts above this line.
}
