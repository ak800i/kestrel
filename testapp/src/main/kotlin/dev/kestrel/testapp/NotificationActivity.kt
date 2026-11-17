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
 * `cancel` it removes the notification instead. The tests grant the test app the notification permission.
 */
class NotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notifications = getSystemService(NotificationManager::class.java)
        if (intent.getBooleanExtra(EXTRA_CANCEL, false)) {
            notifications.cancel(ID)
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
        const val TITLE = "Fixture reminder"
        private const val ID = 1
        private const val CHANNEL = "fixtures"
        private const val TEXT = "The first line of a long text."
        private val LONG_TEXT = (1..6).joinToString("\n") { "Line $it of a long text, which the expanded notification shows." }
    }
}
