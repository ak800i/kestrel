package dev.kestrel.datatools.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kestrel's notification listener, which `list_notifications` reads (design D9 of sub-project 5). Android binds it only
 * after the owner gives notification access, and Kestrel reads it only after [onListenerConnected]. It keeps nothing and
 * logs nothing: each read takes the notifications that are active at that moment.
 */
class KestrelNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        state.value = true
    }

    override fun onListenerDisconnected() = forget()

    override fun onDestroy() {
        forget()
        super.onDestroy()
    }

    private fun forget() {
        if (instance === this) {
            instance = null
            state.value = false
        }
    }

    /** The active notifications without group summaries, which repeat their children. Off the main thread. */
    internal fun facts(): List<NotificationFacts> {
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            // Android disconnected the listener between the check and the read.
            return emptyList()
        }
        return active.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.map(::facts)
    }

    private fun facts(sbn: StatusBarNotification): NotificationFacts {
        val extras = sbn.notification.extras
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
            .orEmpty()
            .map { it.senderPerson?.name?.toString() to it.text?.toString() }
        return NotificationFacts(
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName),
            postTime = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            messages = messages,
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )
    }

    private fun label(packageName: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        @Volatile
        internal var instance: KestrelNotificationListener? = null
            private set

        private val state = MutableStateFlow(false)

        /** True while Android has the listener connected. */
        val connected: StateFlow<Boolean> = state.asStateFlow()

        fun component(context: Context): ComponentName = ComponentName(context, KestrelNotificationListener::class.java)

        /** Whether the owner gave Kestrel notification access. */
        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

        /** Android's page of notification access for Kestrel's listener, from which the owner turns it on. */
        fun accessSettings(context: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
