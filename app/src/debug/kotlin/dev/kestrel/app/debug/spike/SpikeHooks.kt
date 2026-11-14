package dev.kestrel.app.debug.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import dev.kestrel.app.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The test-hook commands of spike S1. `scripts/hook.ps1` lists them. */
internal class SpikeHooks(private val context: Context, private val graph: AppGraph) {
    fun run(command: String, intent: Intent): String = when (command) {
        "LISTEN" -> listen(intent)
        "LISTEN_NOTIFICATION" -> listenNotification()
        "LISTEN_AFTER_BOOT" -> {
            SpikeListening.requestListenAtNextStart(context)
            "Kestrel records 15 s after its accessibility service connects at the next start of its process"
        }
        "LOCATION" -> location(intent)
        else -> results(intent)
    }

    /** Starts a recording after a delay, so that the hook's own broadcast is long over when it starts. */
    private fun listen(intent: Intent): String {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, DEFAULT_DELAY_SECONDS)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
        graph.scope.launch {
            delay(delaySeconds * 1_000L)
            SpikeListening.start(context, source, seconds)
        }
        return "recording in $delaySeconds s for $seconds s"
    }

    private fun listenNotification(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Spike", NotificationManager.IMPORTANCE_HIGH))
        val start = PendingIntent.getForegroundService(
            context,
            0,
            SpikeListeningService.intent(context, "notification", DEFAULT_SECONDS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kestrel spike")
            .addAction(Notification.Action.Builder(null, ACTION_LABEL, start).build())
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLIS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return "posted a notification with the action '$ACTION_LABEL'"
    }

    private fun location(intent: Intent): String {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, 0)
        graph.scope.launch {
            delay(delaySeconds * 1_000L)
            SpikeListening.record(context, "location source=$source ${locate()}")
        }
        return "locating in $delaySeconds s"
    }

    /** Says only whether a location came, never where the device is. */
    private suspend fun locate(): String {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) return "result=location-off"
        val provider = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        return try {
            val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                }
            }
            "provider=$provider result=${if (location == null) "none" else "found"}"
        } catch (e: SecurityException) {
            "provider=$provider result=SecurityException"
        }
    }

    private fun results(intent: Intent): String {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            SpikeListening.clearResults(context)
            return "cleared"
        }
        return SpikeListening.results(context).ifEmpty { "none yet" }
    }

    companion object {
        val COMMANDS = setOf("LISTEN", "LISTEN_NOTIFICATION", "LISTEN_AFTER_BOOT", "LOCATION", "SPIKE_RESULTS")
        private const val CHANNEL = "spike"
        private const val NOTIFICATION_ID = 7_002
        private const val ACTION_LABEL = "Start listening"
        private const val NOTIFICATION_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_DELAY_SECONDS = 15
        private const val DEFAULT_SECONDS = 3
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_CLEAR = "clear"
    }
}
