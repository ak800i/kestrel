package dev.kestrel.app.listen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import dev.kestrel.app.R

/**
 * Kestrel's notifications about problems (design spec, section 14.6), because the feedback bar exists only while
 * Kestrel's accessibility service runs. The owner can swipe each of them away. Without the notification permission they
 * post nothing. Any thread may call them.
 */
internal class ProblemNotifications(private val context: Context) : StopNotices {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Says why Kestrel stopped listening or could not start, with "Start listening" when a new start can help. */
    override fun show(reason: StopReason) {
        if (!allowed()) return
        notifications.createNotificationChannel(
            NotificationChannel(PROBLEMS, context.getString(R.string.problems_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val title = if (reason.starting) R.string.problem_title_not_started else R.string.problem_title_stopped
        val builder = Notification.Builder(context, PROBLEMS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(title))
            .setContentText(reason.text)
            .setStyle(Notification.BigTextStyle().bigText(reason.text))
            .setContentIntent(ListeningService.openKestrel(context))
            .setAutoCancel(true)
        if (reason.restart) {
            val start = ListeningService.startIntent(context)
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.problem_start), start).build())
        }
        notifications.notify(STOPPED_ID, builder.build())
    }

    override fun clear() = notifications.cancel(STOPPED_ID)

    private fun allowed() =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** The notification that says why Kestrel stopped listening or could not start. */
        internal const val STOPPED_ID = 7_102
        private const val PROBLEMS = "problems"
    }
}
