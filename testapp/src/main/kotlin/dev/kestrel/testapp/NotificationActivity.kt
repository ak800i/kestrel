package dev.kestrel.testapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle

/**
 * Posts the fixture notification and closes at once, for "expand", "collapse" and "dismiss" in the notification shade
 * (design spec, Appendix A): a notification with a long text, which the shade can expand, and an action. With the extra
 * `cancel` it removes the notification instead. With the extra `headsUp` it posts a second notification on a channel of
 * high importance, which Android shows as a heads-up, with [MARKER] in its text, which no observation may hold (design
 * D9 of sub-project 5). The tests grant the test app the notification permission.
 */
class NotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notifications = getSystemService(NotificationManager::class.java)
        if (intent.getBooleanExtra(EXTRA_CANCEL, false)) {
            notifications.cancel(ID)
            notifications.cancel(HEADS_UP_ID)
        } else if (intent.getBooleanExtra(EXTRA_HEADS_UP, false)) {
            notifications.createNotificationChannel(NotificationChannel(HEADS_UP_CHANNEL, "Fixture alerts", NotificationManager.IMPORTANCE_HIGH))
            notifications.notify(
                HEADS_UP_ID,
                Notification.Builder(this, HEADS_UP_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(HEADS_UP_TITLE)
                    .setContentText("Alert $MARKER")
                    .build(),
            )
        } else {
            // The default importance puts the notification into the shade without a heads-up.
            val channel = NotificationChannel(CHANNEL, "Fixtures", NotificationManager.IMPORTANCE_DEFAULT)
            notifications.createNotificationChannel(channel)
            val open = PendingIntent.getActivity(this, 0, Intent(this, CommandsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_send)
            notifications.notify(
                ID,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(TITLE)
                    .setContentText(TEXT)
                    .setStyle(Notification.BigTextStyle().bigText(LONG_TEXT))
                    .setContentIntent(open)
                    .addAction(Notification.Action.Builder(icon, "Mark done", open).build())
                    .build(),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_CANCEL = "cancel"
        const val EXTRA_HEADS_UP = "headsUp"
        const val TITLE = "Fixture reminder"
        const val HEADS_UP_TITLE = "Fixture alert"
        const val MARKER = "KX4711"
        private const val ID = 1
        private const val HEADS_UP_ID = 2
        private const val CHANNEL = "fixtures"
        private const val HEADS_UP_CHANNEL = "fixture-alerts"
        private const val TEXT = "The first line of a long text."
        private val LONG_TEXT = (1..6).joinToString("\n") { "Line $it of a long text, which the expanded notification shows." }
    }
}
