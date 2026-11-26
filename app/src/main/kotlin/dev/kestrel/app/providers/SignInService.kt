package dev.kestrel.app.providers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.KestrelNotification
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps Kestrel's process in the foreground while a sign-in runs (design D10): without it, Android's freezer stops a
 * browser sign-in about 70 s after the tab opens, and nothing in the tab shows it (prototype P5, q4). It is a `dataSync`
 * service, because `shortService`'s 3 minutes are shorter than a browser callback's 5 and a device code's 15. The
 * settings page starts it before it opens the tab or shows the code; it stops when the sign-in ends, fails or is
 * cancelled, and its notification "Signing in to <provider>" has a Cancel action. When the sign-in succeeded after Kestrel
 * opened a Custom Tab and the settings are not in front, it brings the settings back: P5 showed that Android allows this
 * start while the tab is in Kestrel's task (q6).
 */
internal class SignInService : Service() {
    private val graph get() = (application as KestrelApplication).graph
    private var watcher: Job? = null

    /** The latest start, so that a stop ends the service only when no newer start came, as a sign-in right after a cancel. */
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                graph.providers.cancelSignIn()
                stop()
                return START_NOT_STICKY
            }
            ACTION_RETURN -> {
                returnToKestrel()
                stop()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_PROVIDER).orEmpty()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // For example a start that Android does not allow from the background; the sign-in goes on without it.
            graph.report("Starting the sign-in service", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watcher == null) watcher = graph.scope.launch(Dispatchers.Main) { watch() }
        return START_NOT_STICKY
    }

    /**
     * Leaves the foreground and stops when the sign-in ends, and brings Kestrel back after a successful one. A sign-in can
     * end before this service sees it wait, as without network, so a success or a failure always stops the service; Idle
     * (a cancel) stops it only after a wait, so that a start without a sign-in, as CustomTabSignInTest's, keeps it.
     */
    private suspend fun watch() {
        var waited = false
        graph.providers.signIn.collect { state ->
            when (state) {
                is SignInState.Waiting -> waited = true
                is SignInState.Succeeded -> {
                    if (graph.providers.tabOpened && !graph.timeline.settingsInFront) returnToKestrel()
                    // A profile's "Sign in again" stored its credential already, so its token need not stay in memory.
                    graph.providers.forgetRenewal()
                    stop()
                }
                is SignInState.Failed -> stop()
                SignInState.Idle -> if (waited) stop()
            }
        }
    }

    // Android 15 and later allow a dataSync service 6 hours a day and then call this; a sign-in never needs that long, so
    // it ends with a sentence that says why, and the service stops before Android would end Kestrel.
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.providers.timeOutSignIn()
        stop()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun returnToKestrel() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PAGE, ProviderGraph.PROVIDERS_PAGE)
            .putExtra(MainActivity.EXTRA_RESUME_ADD, true)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            // A later Android or Chrome may refuse the start; the loopback page tells the owner to close the tab.
            graph.report("Returning to Kestrel after a sign-in", e)
        }
    }

    private fun stop() {
        watcher?.cancel()
        watcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A start that Android queued after this one keeps the service, and its onStartCommand puts it in the foreground again.
        stopSelf(latestStartId)
    }

    private fun notification(provider: String): Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.signin_channel), NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, SignInService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.signin_notification, provider))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.signin_cancel), cancel).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "sign-in"
        internal val NOTIFICATION_ID = KestrelNotification.SIGN_IN.id
        private const val EXTRA_PROVIDER = "dev.kestrel.app.extra.PROVIDER"
        private const val ACTION_CANCEL = "dev.kestrel.app.action.CANCEL_SIGN_IN"
        private const val ACTION_RETURN = "dev.kestrel.app.action.RETURN_AFTER_SIGN_IN"
        private const val ACTION_STOP = "dev.kestrel.app.action.STOP_SIGN_IN"

        /** Starts the service for a sign-in to [provider], from the settings activity, which is in front. */
        fun start(context: Context, provider: String) {
            context.startForegroundService(Intent(context, SignInService::class.java).putExtra(EXTRA_PROVIDER, provider))
        }

        /** Brings Kestrel's settings back to the front from the running service, as after a sign-in in a Custom Tab. */
        fun returnToKestrel(context: Context) {
            context.startService(Intent(context, SignInService::class.java).setAction(ACTION_RETURN))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SignInService::class.java).setAction(ACTION_STOP))
        }
    }
}
