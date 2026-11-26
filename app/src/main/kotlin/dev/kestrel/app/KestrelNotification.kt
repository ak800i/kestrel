package dev.kestrel.app

/**
 * Every kind of Kestrel's notifications, each with an [id] of its own. Android keeps one notification per ID and app, so
 * two kinds that shared an ID would replace and cancel each other: the accessibility notice took the held goal's ID and
 * replaced its notification, so the owner lost its Continue (plan 7E, Task 8's Step 8a). The IDs follow the order here,
 * from 7101; a new kind goes at the end, so that no ID of an older kind changes.
 */
internal enum class KestrelNotification {
    LISTENING,
    PROBLEM_STOPPED,
    PROBLEM_ACCESSIBILITY,
    PROBLEM_SETTINGS,
    SIGN_IN,
    GOAL_SERVICE,
    GOAL_POSTED,
    ;

    val id: Int get() = FIRST_ID + ordinal

    private companion object {
        const val FIRST_ID = 7_101
    }
}