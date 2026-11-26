package dev.kestrel.app.goal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.kestrel.app.NotificationGroups
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningService

/**
 * The goal notification on Android (design 10.6 of sub-project 7; plan 7E): its channel "Goals" ([CHANNEL]), the
 * notification that [GoalService] shows in the foreground ([NoticePlace.SERVICE]), and the held goal's plain notification
 * that Kestrel posts without the service after a refusal ([NoticePlace.POSTED], an ID of its own, so that taking it away
 * never touches the service's). A tap opens Kestrel's screen; the lock screen shows only "Kestrel is working on a task",
 * because the current item may hold names. The buttons are broadcasts to [GoalActionReceiver] whose data names the goal
 * that the notice shows, each goal with PendingIntents of its own, and with no inline reply (D8); a button that resumes
 * what Kestrel does asks Android for the owner's unlock first (the owner's decision of 2026-11-25).
 */
internal object GoalNotification {
    const val CHANNEL = "goals"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, GoalTexts.CHANNEL, NotificationManager.IMPORTANCE_LOW))
    }

    /** The notification of [notice] in [place]: the service's, or the one posted without it. */
    fun build(context: Context, notice: GoalNotice, place: NoticePlace): Notification {
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
            // Not in Android's automatic group with Kestrel's other notifications (NotificationGroups says on which API levels).
            .setGroup(NotificationGroups.alone(place.id))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
        // A colorized notification of a foreground service is in SystemUI's section for them (AOSP's "ForegroundService"
        // sectioner, in Android 13 and in AOSP's main branch), above the alerting and silent ones, so it is the shade's first
        // notification, which SystemUI expands with its buttons, unless a heads-up, a call or another colorized service's
        // notification comes first, or on API 36 and later a Live Update (design 10.6). Android colorizes only a foreground
        // service's notification, so the one posted without the service is a plain one.
        if (place.foreground) {
            builder.setColorized(true)
                .setColor(context.getColor(R.color.kestrel))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        notice.text?.let { builder.setContentText(it) }
        notice.progress?.let { builder.setProgress(it.total, it.done, false) }
        // Buttons only for a named goal, which GoalNotices gives every notice that has buttons.
        notice.goal?.let { goal ->
            for (action in notice.actions) {
                builder.addAction(
                    Notification.Action.Builder(null, action.label, intentOf(context, action, goal))
                        .setAuthenticationRequired(action.resumes)
                        .build(),
                )
            }
        }
        return builder.build()
    }

    /** Posts [notice] as the held goal's notification without the service, or takes it away when [notice] is null. */
    fun post(context: Context, notice: GoalNotice?) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notice == null) {
            notifications.cancel(NoticePlace.POSTED.id)
            return
        }
        createChannel(context)
        notifications.notify(NoticePlace.POSTED.id, build(context, notice, NoticePlace.POSTED))
    }

    /**
     * The PendingIntent of [action]'s button for [goal], the goal that the notice shows: Open opens Kestrel's screen, the
     * others are broadcasts to [GoalActionReceiver] whose data names the goal (plan 7E). Android matches PendingIntents by
     * their intent's action and data, not by extras, so each goal's buttons get PendingIntents of their own, and a newer
     * goal's notice never rewrites the button of an older notice that SystemUI still holds, as a Continue that waits for the
     * owner's unlock (the second review of plan 7E, R2-I1). Nothing of a PendingIntent is ever updated.
     */
    fun intentOf(context: Context, action: GoalAction, goal: NoticeGoal): PendingIntent =
        if (action.intent == null) {
            ListeningService.openKestrel(context)
        } else {
            val intent = Intent(context, GoalActionReceiver::class.java)
                .setAction(action.broadcast)
                .setData(Uri.fromParts(SCHEME, goal.key, null))
            PendingIntent.getBroadcast(context, action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE)
        }

    /** The goal that a button's broadcast names in its data; null when it names none. */
    fun goalOf(intent: Intent): NoticeGoal? = intent.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart.let(NoticeGoal::ofKey)

    private const val SCHEME = "kestrel-goal"
}
