package dev.kestrel.app.debug

import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalServiceStatus

/**
 * A button's PendingIntent as Android holds it on Kestrel's own posted goal notification (plan 7E, Task 8, Step 8a), for
 * the hook goal-control do=send-later: the very intent that the owner's tap on the button sends, taken from the
 * notification that Android shows, never built again. Generic in the intent, so that the choice is tested without Android.
 */
internal object NoticeIntents {
    /** One of Kestrel's posted notifications: its ID and its buttons, each as its label and its intent. */
    data class Posted<T>(val id: Int, val buttons: List<Pair<String, T>>)

    /**
     * The ID of the notification that shows [action] now: the held goal's notification that Kestrel posted without the
     * service, or else the service's own; null when neither offers [action].
     */
    fun idOf(status: GoalServiceStatus, action: GoalAction): Int? = when {
        status.posted?.actions?.contains(action) == true -> GoalNotification.POSTED_ID
        status.notice?.actions?.contains(action) == true -> GoalNotification.SERVICE_ID
        else -> null
    }

    /** The intent of the button [label] on Kestrel's posted notification [id]; null when Android shows no such button. */
    fun <T> intentOf(posted: List<Posted<T>>, id: Int, label: String): T? =
        posted.firstOrNull { it.id == id }?.buttons?.firstOrNull { it.first == label }?.second
}
