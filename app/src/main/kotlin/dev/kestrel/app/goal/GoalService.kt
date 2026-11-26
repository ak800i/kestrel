package dev.kestrel.app.goal

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.kestrel.app.KestrelApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a goal working while Kestrel's screen is not in front (design 10.6 of sub-project 7; plan 7E): a foreground service
 * of the type specialUse from Android 14 on, with mediaPlayback added while Kestrel speaks a typed command's reply with
 * listening off, and of no type on Android 13, which checks none. [GoalForeground] decides when it runs, what it shows and
 * with which types; the service follows [GoalForeground.shown] and tells it when it is in the foreground, refused or gone.
 * Every start calls startForeground(), as Android requires after startForegroundService(), and a start that comes after
 * the goal's end stops the service again; a refused new start of a service that runs leaves it running. Android calls
 * every callback on the main thread.
 */
class GoalService : Service() {
    private val graph get() = (application as KestrelApplication).graph
    private val foreground: GoalForeground get() = graph.goalForeground
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: NotificationManager
    private var latestStart = 0
    private var types: Set<ForegroundType> = emptySet()

    @Volatile
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        notifications = getSystemService(NotificationManager::class.java)
        GoalNotification.createChannel(this)
        scope.launch { foreground.shown.collect { shown -> if (inForeground) follow(shown) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val shown = foreground.shown.value
        val wanted = shown?.types ?: GoalServiceRules.types(Build.VERSION.SDK_INT, speaking = false)
        try {
            startForeground(NoticePlace.SERVICE.id, GoalNotification.build(this, shown?.notice ?: GoalNotices.CLOSING, NoticePlace.SERVICE), mask(wanted))
        } catch (e: Exception) {
            // For example ForegroundServiceStartNotAllowedException: Android refused the start (design 10.6 of sub-project 7).
            // A service that is in the foreground already stays, as the rules say.
            foreground.failed(e)
            if (!inForeground) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        inForeground = true
        types = wanted
        foreground.started(wanted)
        // A start that came after the goal's end has shown the end, as Android requires, and stops.
        if (foreground.shown.value == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground.destroyed()
        super.onDestroy()
    }

    // A new notice updates the notification; new types need startForeground() again, which Android may refuse, and then
    // the service keeps its types and shows the notice.
    private fun follow(shown: Shown?) {
        if (shown == null) {
            stopSelfResult(latestStart)
            return
        }
        if (shown.types != types) {
            try {
                startForeground(NoticePlace.SERVICE.id, GoalNotification.build(this, shown.notice, NoticePlace.SERVICE), mask(shown.types))
                types = shown.types
                foreground.applied(shown.types)
                return
            } catch (e: Exception) {
                graph.report("Changing the goal service's types", e)
            }
        }
        notifications.notify(NoticePlace.SERVICE.id, GoalNotification.build(this, shown.notice, NoticePlace.SERVICE))
    }

    companion object {
        // The service while it runs, for runningTypes(); onDestroy clears it, so it cannot keep a destroyed service alive.
        @Volatile
        private var current: GoalService? = null

        /** Asks Android to start the service in the foreground; throws what startForegroundService() throws. */
        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, GoalService::class.java))
        }

        /** The types with which the service is in the foreground, or null while none is: for the hooks and the device tests. */
        internal fun runningTypes(): Set<ForegroundType>? = current?.takeIf { it.inForeground }?.let { typesOf(it.foregroundServiceType) }

        private fun mask(types: Set<ForegroundType>): Int = types.fold(0) { bits, type -> bits or bitOf(type) }

        private fun typesOf(mask: Int): Set<ForegroundType> = ForegroundType.entries.filterTo(mutableSetOf()) { mask and bitOf(it) != 0 }

        private fun bitOf(type: ForegroundType): Int = when (type) {
            ForegroundType.SPECIAL_USE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            ForegroundType.MEDIA_PLAYBACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
    }
}
