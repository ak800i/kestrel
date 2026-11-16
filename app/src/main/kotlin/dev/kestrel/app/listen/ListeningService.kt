package dev.kestrel.app.listen

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Kestrel listening (design spec, sections 7.2, 8.5 and 8.6): a foreground service of the types microphone and
 * media playback, because Android lets Kestrel record and play audio in the background only while such a service runs
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, and tells [Listening] whether the screen is on and the phone unlocked. It stops itself
 * when [Listening] asks, unless a newer start is still on its way to it. Android calls every callback on the main thread.
 */
class ListeningService : Service() {
    private val listening: Listening get() = (application as KestrelApplication).graph.listening
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    // The id of the last start that onStartCommand handled; only the main thread uses it.
    private var lastStartId = 0
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) =
            reportScreen(unlocked = intent.action == Intent.ACTION_USER_PRESENT)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.listening_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, and Android gives another
        // app's broadcasts only to an exported receiver. All three are protected broadcasts, which other apps cannot send.
        registerReceiver(screen, filter, Context.RECEIVER_EXPORTED)
        // Before the voice session opens, and so before the microphone's first frame.
        reportScreen()
        scope.launch {
            listening.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                // Off: the service is about to stop, and its notification goes with it.
                if (foreground && mode != Mode.OFF) notifications.notify(NOTIFICATION_ID, notification(mode))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Android requires this soon after every startForegroundService(), also for a service that already runs.
        try {
            startForeground(NOTIFICATION_ID, notification(listening.state.value.mode), FOREGROUND_TYPES)
        } catch (e: Exception) {
            // For example a SecurityException without the microphone permission, or a start from the background that
            // Android does not allow (section 14.6). A newer start that is on its way keeps the service, and fails or
            // reports in turn.
            listening.onForegroundFailed(e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        foreground = true
        listening.onForeground(this)
        when (intent?.action) {
            ACTION_PAUSE -> listening.send(Event.Pause)
            ACTION_RESUME -> listening.send(Event.Resume)
            ACTION_STOP -> listening.send(Event.TurnOff)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        unregisterReceiver(screen)
        // After this, no update of the notification can bring it back.
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        listening.onServiceDestroyed(this)
        super.onDestroy()
    }

    /**
     * Section 8.5: Kestrel records only while the screen is on and the phone unlocked. [unlocked]: the broadcast was
     * USER_PRESENT, which says that the owner unlocked the phone, even if KeyguardManager learns it a moment later.
     */
    private fun reportScreen(unlocked: Boolean = false) {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = !unlocked && getSystemService(KeyguardManager::class.java).isKeyguardLocked
        listening.send(Event.Screen(on = interactive && !locked))
    }

    private fun notification(mode: Mode): Notification {
        val listeningNow = mode == Mode.LISTENING
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (listeningNow) R.string.listening_title_listening else R.string.listening_title_paused
        val toggle = if (listeningNow) action(R.string.listening_pause, ACTION_PAUSE) else action(R.string.listening_resume, ACTION_RESUME)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(title))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(toggle)
            .addAction(action(R.string.listening_stop, ACTION_STOP))
            .build()
    }

    private fun action(title: Int, action: String): Notification.Action =
        Notification.Action.Builder(null, getString(title), actionIntent(this, action)).build()

    companion object {
        internal const val NOTIFICATION_ID = 7_101
        internal const val ACTION_PAUSE = "dev.kestrel.listen.PAUSE"
        internal const val ACTION_RESUME = "dev.kestrel.listen.RESUME"
        internal const val ACTION_STOP = "dev.kestrel.listen.STOP"
        private const val CHANNEL = "listening"
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        // The running service, so that it can stop itself when Listening asks. Only the main thread reads or writes it,
        // and onDestroy clears it, so it cannot keep a destroyed service alive.
        @SuppressLint("StaticFieldLeak")
        private var current: ListeningService? = null

        /** The intent of the notification's action [action]: it starts the service with that action. */
        internal fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, ListeningService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Starts and stops the service for [Listening]. */
        internal fun control(context: Context): ServiceControl = object : ServiceControl {
            private val main = Handler(Looper.getMainLooper())

            override fun start() {
                context.startForegroundService(Intent(context, ListeningService::class.java))
            }

            // stopService() would also bring down a service whose newest start has not reached startForeground() yet,
            // and Android would then end Kestrel's process. stopSelfResult() leaves such a service running, and its start
            // reports to Listening, which asks again.
            override fun stop() {
                main.post { current?.let { it.stopSelfResult(it.lastStartId) } }
            }
        }
    }
}
