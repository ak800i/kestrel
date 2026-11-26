package dev.kestrel.app.listen

import dev.kestrel.app.KestrelNotification
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.kestrel.app.MainActivity
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

    /**
     * Kestrel's accessibility service disconnected, for example because the owner turned it off. Kestrel cannot use the
     * screen, the accessibility button or the shortcut until the service is on again; if Kestrel is listening, it goes
     * on listening (prototype P2). A tap opens Android's accessibility settings, where the owner turns the service on, or
     * off and on again when his stop in "Active apps" left it unbound while the setting still names it (UnboundServiceCheck).
     * Android's page of one service (ACTION_ACCESSIBILITY_DETAILS_SETTINGS) is a system API, and its activity needs a
     * permission that only the system and the installer hold. The channel makes no sound, because the owner usually
     * turned the service off on purpose.
     */
    fun accessibilityServiceOff() {
        if (!allowed()) return
        notifications.createNotificationChannel(
            NotificationChannel(ACCESSIBILITY, context.getString(R.string.accessibility_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val text = context.getString(R.string.problem_service_off_text)
        val settings = PendingIntent.getActivity(
            context,
            0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, ACCESSIBILITY)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.problem_service_off_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settings)
            .build()
        notifications.notify(ACCESSIBILITY_ID, notification)
    }

    /** Kestrel's accessibility service connected, so the notification of [accessibilityServiceOff] goes. */
    fun accessibilityServiceOn() = notifications.cancel(ACCESSIBILITY_ID)

    /**
     * Android did not bring Kestrel's settings to the front after "Kestrel, open settings" (design spec, section 14): a
     * tap on this notification opens them, because Android lets the tap on a notification start an activity. Each post
     * gets an intent of its own, so that the tap reaches the settings app also when something covers it in Kestrel's
     * task.
     */
    fun offerSettings() {
        if (!allowed()) return
        notifications.createNotificationChannel(
            NotificationChannel(PROBLEMS, context.getString(R.string.problems_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = context.getString(R.string.problem_settings_text)
        val open = PendingIntent.getActivity(context, 0, MainActivity.freshIntent(context), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, PROBLEMS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.problem_settings_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notifications.notify(SETTINGS_ID, notification)
    }

    /** Kestrel's settings came to the front, so the notification of [offerSettings] goes. */
    fun settingsOpened() = notifications.cancel(SETTINGS_ID)

    private fun allowed() =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** The notification that says why Kestrel stopped listening or could not start. */
        internal val STOPPED_ID = KestrelNotification.PROBLEM_STOPPED.id

        /** The notification that says that Kestrel's accessibility service stopped. */
        internal val ACCESSIBILITY_ID = KestrelNotification.PROBLEM_ACCESSIBILITY.id

        /** The notification that opens Kestrel's settings after Android kept them back. */
        internal val SETTINGS_ID = KestrelNotification.PROBLEM_SETTINGS.id
        private const val PROBLEMS = "problems"
        private const val ACCESSIBILITY = "accessibility"
    }
}
