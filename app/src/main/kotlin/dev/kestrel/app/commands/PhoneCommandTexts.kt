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

    /** [name] is the owner's words, which Kestrel says and never logs. */
    fun cantFindApp(name: String) = "I can't find an app called $name."

    const val WORK_APPS_TOO_OLD = "I can't open work apps on this version of Android."
    const val NO_WORK_PROFILE = "There's no work profile on this phone."
    const val WORK_APPS_PAUSED = "Work apps are paused."

    /** "Which one? 1, Chrome. 2, Chrome Beta." for apps and contacts; the names are said and kept on the bar. */
    fun whichOne(names: List<String>) = "Which one? " + names.withIndex().joinToString(" ") { "${it.index + 1}, ${it.value}." }

    fun noChoice(number: Int) = "There's no number $number in the list."

    const val SAY_THE_NUMBER = "Say the number."

    const val WIFI_ALREADY_ON = "Wi-Fi is already on."
    const val WIFI_ALREADY_OFF = "Wi-Fi is already off."
    const val NO_WIFI_SWITCH = "I can't find the Wi-Fi switch."
    const val WIFI_NOT_CHANGED = "Wi-Fi didn't change."
    const val BLUETOOTH_ALREADY_ON = "Bluetooth is already on."
    const val BLUETOOTH_ALREADY_OFF = "Bluetooth is already off."
    const val NO_BLUETOOTH_SWITCH = "I can't find the Bluetooth switch."
    const val BLUETOOTH_NOT_CHANGED = "Bluetooth didn't change."

    const val HIGHEST = "The volume is already at its highest."
    const val LOWEST = "The volume is already at its lowest."
    const val VOLUME_NOT_CHANGED = "Android didn't change the volume."
    const val RINGER_WHILE_SILENT = "Android doesn't let me change the ringer while the phone is silent."
    const val MUTE_ALARM = "Android doesn't let me mute the alarm volume."
    const val MUTE_CALL = "Android doesn't let me mute the call volume."
    const val ALREADY_MUTED = "It's already muted."
    const val NOT_MUTED = "It isn't muted."
    const val ALREADY_VIBRATE = "The phone is already on vibrate."
    const val ALREADY_SILENT = "The phone is already silent."
    const val NO_MEDIA = "No media app reacted."
    const val NOT_MAGNIFIED = "The screen is not magnified."
    const val MOST_MAGNIFIED = "The screen is magnified as much as it can be."

    // Tasks 2 to 6 of plan 3C add their texts above this line.
}
