package dev.kestrel.app.goal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningService

/**
 * The goal notification on Android (design 10.6 of sub-project 7; plan 7E): its channel "Goals", the notification that
 * [GoalService] shows in the foreground ([SERVICE_ID]), and the held goal's notification that Kestrel posts without the
 * service after a refusal ([POSTED_ID], an ID of its own, so that taking it away never touches the service's). A tap opens
 * Kestrel's screen; the lock screen shows only "Kestrel is working on a task", because the current item may hold names.
 * The buttons are broadcasts to [GoalActionReceiver] whose data names the goal that the notice shows, each goal with
 * PendingIntents of its own, and with no inline reply (D8);
 * a button that resumes what Kestrel does asks Android for the owner's unlock first (the owner's decision of 2026-11-25).
 */
internal object GoalNotification {
    const val SERVICE_ID = 7_102
    const val POSTED_ID = 7_103
    private const val CHANNEL = "goals"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, GoalTexts.CHANNEL, NotificationManager.IMPORTANCE_LOW))
    }

    fun build(context: Context, notice: GoalNotice): Notification {
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(GoalTexts.PUBLIC)
            .build()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(notice.title)
            .setContentIntent(ListeningService.openKestrel(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // A colorized notification of a foreground service: SystemUI shows it at the top of the shade, expanded with its
            // buttons, and Android never puts it in a group with Kestrel's other notifications, whose collapsed group would
            // hide them (design 10.6). Android colorizes only a foreground service's notification, so the held goal's
            // notification that Kestrel posts without the service after a refusal is a plain one.
            .setColorized(true)
            .setColor(context.getColor(R.color.kestrel))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        notice.text?.let { builder.setContentText(it) }
        notice.progress?.let { builder.setProgress(it.total, it.done, false) }
        for (action in notice.actions) {
            builder.addAction(
                Notification.Action.Builder(null, action.label, intentOf(context, action, notice.goal))
                    .setAuthenticationRequired(action.resumes)
                    .build(),
            )
        }
        return builder.build()
    }

    /** Posts [notice] as the held goal's notification without the service, or takes it away when [notice] is null. */
    fun post(context: Context, notice: GoalNotice?) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notice == null) {
            notifications.cancel(POSTED_ID)
            return
        }
        createChannel(context)
        notifications.notify(POSTED_ID, build(context, notice))
    }

    /**
     * The PendingIntent of [action]'s button for [goal], the goal that the notice shows: Open opens Kestrel's screen, the
     * others are broadcasts to [GoalActionReceiver] whose data names the goal (plan 7E). Android matches PendingIntents by
     * their intent's action and data, not by extras, so each goal's buttons get PendingIntents of their own, and a newer
     * goal's notice never rewrites the button of an older notice that SystemUI still holds, as a Continue that waits for the
     * owner's unlock (the second review of plan 7E, R2-I1). Nothing of a PendingIntent is ever updated.
     */
    fun intentOf(context: Context, action: GoalAction, goal: NoticeGoal?): PendingIntent =
        if (action.intent == null) {
            ListeningService.openKestrel(context)
        } else {
            val intent = Intent(context, GoalActionReceiver::class.java).setAction(action.broadcast)
            goal?.let { intent.setData(Uri.fromParts(SCHEME, it.key, null)) }
            PendingIntent.getBroadcast(context, action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE)
        }

    /** The goal that a button's broadcast names in its data; null when it names none. */
    fun goalOf(intent: Intent): NoticeGoal? = intent.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart.let(NoticeGoal::ofKey)

    private const val SCHEME = "kestrel-goal"
}
