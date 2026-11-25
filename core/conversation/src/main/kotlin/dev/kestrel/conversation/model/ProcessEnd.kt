package dev.kestrel.conversation.model

/**
 * How Kestrel's previous process ended, as `:app` reads it from Android's exit records at the next start (D11 of
 * sub-project 7; plan 7E). The restore ends a goal as "stop" ends it only after [OwnerStopped].
 */
sealed interface ProcessEnd {
    /** The owner stopped Kestrel at [atWall]: Stop in Android's "Active apps", a force stop, or removing it from Recents. */
    data class OwnerStopped(val atWall: Long) : ProcessEnd

    /** Any other end, or a stop that cannot be told from an update of Kestrel, which Android 13 records alike. */
    data object Other : ProcessEnd
}
