package dev.kestrel.app.listen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.KestrelNotification
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.NotificationGroups
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.phone.PhoneWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Kestrel listening (design spec, sections 7.2, 8.5 and 8.6): a foreground service of the types microphone and
 * media playback, because Android lets Kestrel record and play audio in the background only while such a service runs
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, with only Stop during a call, or without actions once Kestrel is turning off, and
 * tells [Listening] whether the screen is on and the phone unlocked, from the app graph's
 * [dev.kestrel.app.phone.PhoneSignals], which the conversation shares (plan 7D). The actions are broadcasts to
 * [NotificationActionReceiver], so they need no new start of the service. Since Android 14 the owner can swipe the
 * notification away while the phone is unlocked; it comes back with the next change of mode. The service stops itself
 * when [Listening] asks, unless Android gave it a start that Listening has not seen yet. Android calls every callback
 * on the main thread.
 */
class ListeningService : Service() {
    private val listening: Listening get() = (application as KestrelApplication).graph.listening
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private lateinit var notifications: NotificationManager

    // The service's watch of the screen and the lock (section 8.5), from its creation to its end. Only the main thread
    // uses it.
    private var screenWatch: PhoneWatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.listening_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // The reading at once, before the voice session opens, and so before the microphone's first frame; then every
        // change.
        screenWatch = (application as KestrelApplication).graph.phoneSignals.watchScreen { status ->
            listening.send(Event.Screen(status.reading.unlocked))
        }
        scope.launch {
            listening.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                // Off: the notification says that Kestrel is turning off, without actions, until the service stops.
                if (foreground) notifications.notify(NOTIFICATION_ID, notification(mode))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The A71 session times a start through the problems notification's "Start listening" from here (plan 2E).
        if (intent?.action == ACTION_START) (application as KestrelApplication).graph.timeline.start("notification")
        // Android requires this after a startForegroundService() unless the service is already in the foreground. It can
        // also ignore the call without an exception, so the service calls it after every start.
        try {
            // "Start listening" turns Kestrel on, so the notification that startForeground() posts, possibly after the
            // collector's update, must not say that Kestrel is turning off.
            val mode = listening.state.value.mode
            startForeground(
                NOTIFICATION_ID,
                notification(if (mode == Mode.OFF && intent?.action == ACTION_START) Mode.LISTENING else mode),
                FOREGROUND_TYPES,
            )
        } catch (e: Exception) {
            // For example a SecurityException without the microphone permission, or a start from the background that
            // Android does not allow (section 14.6). A newer start that is on its way keeps the service, and fails or
            // reports in turn.
            listening.onForegroundFailed(e, startAction = intent?.action == ACTION_START)
            if (stopSelfResult(startId)) foreground = false
            return START_NOT_STICKY
        }
        foreground = true
        // startForeground() posts its notification later, from a thread of Android's own, so it can arrive after a newer
        // update from the collector in onCreate. One more update a moment later shows the current mode.
        scope.launch {
            delay(REPOST_MILLIS)
            if (foreground) notifications.notify(NOTIFICATION_ID, notification(listening.state.value.mode))
        }
        // The problems notification's "Start listening" asks for a start that Listening adopts (section 14.6). Listening
        // names the start ID when it asks the service to stop.
        if (intent?.action == ACTION_START) {
            listening.onStartAction(this, startId)
        } else {
            listening.onForeground(this, startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        screenWatch?.stop()
        screenWatch = null
        // After this, no update of the notification can bring it back.
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        listening.onServiceDestroyed(this)
        super.onDestroy()
    }

    /**
     * The notification of [mode]. Off: the service is about to stop, so it offers no actions until then. During a call it
     * offers only Stop.
     */
    private fun notification(mode: Mode): Notification {
        val title = when (mode) {
            Mode.LISTENING -> R.string.listening_title_listening
            Mode.PAUSED -> R.string.listening_title_paused
            Mode.RINGING -> R.string.listening_title_ringing
            Mode.IN_CALL -> R.string.listening_title_in_call
            Mode.OFF -> R.string.listening_title_stopping
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(title))
            .setContentIntent(openKestrel(this))
            .setOngoing(true)
            // Not in Android's automatic group with Kestrel's other notifications (NotificationGroups).
            .setGroup(NotificationGroups.alone(NOTIFICATION_ID))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // During a call the tile and the shortcut choose the mode that comes back after it (section 8.5).
        when (mode) {
            Mode.LISTENING -> builder.addAction(action(R.string.listening_pause, ACTION_PAUSE))
            Mode.PAUSED -> builder.addAction(action(R.string.listening_resume, ACTION_RESUME))
            Mode.RINGING, Mode.IN_CALL, Mode.OFF -> Unit
        }
        if (mode != Mode.OFF) builder.addAction(action(R.string.listening_stop, ACTION_STOP))
        return builder.build()
    }

    private fun action(title: Int, action: String): Notification.Action =
        Notification.Action.Builder(null, getString(title), actionIntent(this, action)).build()

    companion object {
        internal val NOTIFICATION_ID = KestrelNotification.LISTENING.id
        internal const val ACTION_PAUSE = "dev.kestrel.listen.PAUSE"
        internal const val ACTION_RESUME = "dev.kestrel.listen.RESUME"
        internal const val ACTION_STOP = "dev.kestrel.listen.STOP"

        /** The problems notification's "Start listening" (design spec, section 14.6). */
        internal const val ACTION_START = "dev.kestrel.listen.START"
        private const val CHANNEL = "listening"
        private const val REPOST_MILLIS = 1_000L
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        // The running service, so that it can stop itself when Listening asks. Only the main thread reads or writes it,
        // and onDestroy clears it, so it cannot keep a destroyed service alive.
        @SuppressLint("StaticFieldLeak")
        private var current: ListeningService? = null

        /**
         * The running service, for the device tests and the debug hook voice-times, or null while none runs. Only the main
         * thread may call it.
         */
        internal fun running(): ListeningService? = current

        /**
         * The intent of the problems notification's "Start listening": it starts the service with [ACTION_START]. A
         * service's PendingIntent lets the service record after the tap (spike S1); a broadcast would leave it only
         * about 9 s to call startForeground() with the microphone (prototype P2).
         */
        internal fun startIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, ListeningService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Opens Kestrel's conversation screen with the launcher's own intent (design 10.1 of sub-project 7), so that a tap
         * brings Kestrel's task to the front instead of adding another screen. The listening notification and the goal
         * notification (plan 7E) share it.
         */
        internal fun openKestrel(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConversationActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** The intent of the notification's action [action]: a broadcast to [NotificationActionReceiver]. */
        internal fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Starts and stops the service for [Listening]. */
        internal fun control(context: Context): ServiceControl = object : ServiceControl {
            private val main = Handler(Looper.getMainLooper())

            override fun start() {
                context.startForegroundService(Intent(context, ListeningService::class.java))
            }

            // stopService() would also bring down a service whose newest start has not reached startForeground() yet,
            // and Android would then end Kestrel's process. stopSelfResult() leaves such a service running, as it leaves
            // one whose newest start has not reached Listening yet. That start reports to Listening, which decides again.
            override fun stop(instance: Any?, startId: Int) {
                // A stopped service must not post its notification again: without a foreground record nothing would
                // remove it. A new instance started after the one that Listening named, so its start IDs count anew.
                main.post {
                    current?.takeIf { it === instance }?.let { if (it.stopSelfResult(startId)) it.foreground = false }
                }
            }
        }
    }
}
