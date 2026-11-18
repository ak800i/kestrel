package dev.kestrel.app.commands

import dev.kestrel.commands.phone.ContactMatcher

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

    const val NEED_CONTACTS = "I need access to your contacts to call by name. " +
        "Allow it on the screen that I opened, then say it again."

    /** [name] is the owner's words or a contact's name, which Kestrel says and never logs. */
    fun cantFindContact(name: String) = "I can't find $name in your contacts."

    fun tooManyContacts(count: Int, name: String) = "There are $count contacts like $name. Say the full name."

    fun noNumber(name: String) = "$name has no phone number."

    fun noNumberOfKind(name: String, kind: String) = "$name has no $kind number."

    /** "Which number? Mobile or work?", or with numbers when two numbers have the same kind: "Which number? 1, mobile. 2, mobile." */
    fun whichNumber(kinds: List<String>): String {
        if (kinds.map(ContactMatcher::kindOf).distinct().size < kinds.size) {
            return "Which number? " + kinds.withIndex().joinToString(" ") { "${it.index + 1}, ${it.value}." }
        }
        val listed = if (kinds.size == 1) kinds[0] else kinds.dropLast(1).joinToString(", ") + " or " + kinds.last()
        return "Which number? ${listed.replaceFirstChar { it.uppercase() }}?"
    }

    /** A number as Kestrel says it: digit by digit, with "plus", "star" and "hash". */
    fun spoken(digits: String) = digits.map {
        when (it) {
            '+' -> "plus"
            '*' -> "star"
            '#' -> "hash"
            else -> it.toString()
        }
    }.joinToString(" ")

    /** Whom Kestrel calls: "Ana Petrović, mobile", or a spoken number. */
    fun contactAndKind(name: String, kind: String) = "$name, $kind"

    /** The announcement: "Calling Ana Petrović, mobile." */
    fun calling(whom: String) = "Calling $whom."

    /** The countdown on the bar: "Calling Ana Petrović, mobile, in 4". */
    fun callingIn(whom: String, seconds: Int) = "Calling $whom, in $seconds"

    /**
     * A short or code number, one that is no plain number, or an emergency number under [EmergencyPolicy.TOUCH_ONLY]:
     * Kestrel never presses Call for it (design D11).
     */
    fun tapCallYourself(whom: String) = "I opened the phone app with $whom. Tap Call yourself."

    const val OPENED_TAP_CALL = "I opened the phone app. Tap Call yourself."
    const val NO_PHONE_APP = "I couldn't open the phone app."
    const val COULD_NOT_PRESS = "I couldn't press Call. Tap Call yourself."
    const val OTHER_NUMBER = "The phone app shows another number, so I didn't press Call."

    /** Android's call state showed no call within 5 s of the press (the controller's decision on Task 6's review). */
    const val CALL_NOT_STARTED = "The call didn't start."
    const val EMERGENCY_QUESTION = "That may call an emergency number. Say 'yes, call' to do it."
    const val EMERGENCY_TOUCH_ONLY = "Tap it yourself for an emergency call."
    const val CHOOSE_SIM = "Choose the SIM."
    const val NO_PHONE_NUMBER = "I can't find a phone number on the screen."
    const val COULD_NOT_COPY = "I couldn't copy the number."

    /** "Search for" with a digit in a phone app, where a number could start a call (the controller's decision on Task 6). */
    const val SEARCH_NAMES_ONLY = "In the phone app, I search only for names, because a number could start a call."
}
