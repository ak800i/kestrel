package dev.kestrel.commands

/** The global actions of plan 3C (design spec, section 9.3, and Appendix A). */
enum class GlobalCommand { BACK, HOME, NOTIFICATIONS, QUICK_SETTINGS, RECENT_APPS, LOCK_SCREEN, POWER_MENU, SCREENSHOT, ALL_APPS }

enum class RadioKind { WIFI, BLUETOOTH }

enum class StreamKind { MEDIA, RING, ALARM, CALL, NOTIFICATION }

enum class MagnifyAction { IN, OUT, STOP }

/** Whom "call X" calls: a spoken number, or a contact by name with an optional kind of number such as "mobile". */
sealed interface CallTarget {
    data class Number(val digits: String) : CallTarget {
        override fun toString() = "Number(${digits.length} digits)"
    }

    data class Name(val name: String, val kind: String?) : CallTarget {
        override fun toString() = "Name(${name.length} characters, kind=${kind != null})"
    }
}

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A). They are nested here, so that
 * [Call] and [Mute] never clash with the call commands of the Orchestrator's requests.
 */
sealed interface PhoneCommand : Command {
    data class Global(val action: GlobalCommand) : PhoneCommand

    data class OpenApp(val name: String, val work: Boolean) : PhoneCommand {
        override fun toString() = "OpenApp(${name.length} characters, work=$work)"
    }

    data object PreviousApp : PhoneCommand

    data object CircleToSearch : PhoneCommand

    data class Radio(val radio: RadioKind, val on: Boolean) : PhoneCommand

    /** Without [stream], the call volume during a call and the media volume otherwise. */
    data class Volume(val stream: StreamKind?, val up: Boolean) : PhoneCommand

    data class Mute(val stream: StreamKind?, val on: Boolean) : PhoneCommand

    data object Silence : PhoneCommand

    data class Media(val play: Boolean) : PhoneCommand

    data class Magnify(val action: MagnifyAction) : PhoneCommand

    data class Pan(val direction: Direction) : PhoneCommand

    data class Call(val who: CallTarget) : PhoneCommand

    data object CopyPhoneNumber : PhoneCommand
}
