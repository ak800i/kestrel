package dev.kestrel.conversation.engine

/** How a deletion of a past conversation ended (design 9.5 of sub-project 7; plan 7F). */
enum class PastDeletion {
    DELETED,

    /** The conversation is the open one now, as after the owner continued it, so it stays. */
    OPEN,

    /** The index of past conversations cannot be read now, so nothing was deleted; the store tries again by itself later. */
    UNAVAILABLE,

    /** The archive failed; the engine reported the failure by its class. */
    FAILED,
}

/**
 * "Delete all past conversations" as it stands in this process (design 9.5 of sub-project 7): none has run, one runs, or
 * how the last one ended. The engine holds it, so that the settings page shows a running deletion also after the owner
 * left the page and came back, and offers no second one meanwhile (review B-M4 of plan 7F).
 */
sealed interface AllPastDeletion {
    data object Idle : AllPastDeletion

    data object Running : AllPastDeletion

    data class Ended(val outcome: PastDeletion) : AllPastDeletion
}
