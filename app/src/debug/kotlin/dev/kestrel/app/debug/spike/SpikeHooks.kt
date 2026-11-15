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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/** The test-hook commands of spikes S1, S3 and S7. `scripts/hook.ps1` lists them. */
internal class SpikeHooks(private val context: Context, private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "FILES" -> files(intent)
        "LISTEN" -> listen(intent)
        "LISTEN_NOTIFICATION" -> listenNotification()
        "LISTEN_AFTER_BOOT" -> {
            SpikeListening.requestListenAtNextStart(context)
            "Kestrel records 15 s after its accessibility service connects at the next start of its process"
        }
        "LOCATION" -> location(intent)
        "WATCH" -> watch(intent)
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

    /**
     * Lists the files in Kestrel's external files folder, where `adb push` puts the model files (spike S7), with their
     * sizes and SHA-256 hashes, and the folders that Kestrel may not read. With `--es create <folder>`, Kestrel first
     * creates that folder in it.
     */
    private suspend fun files(intent: Intent): String = withContext(Dispatchers.IO) {
        intent.getStringExtra(EXTRA_CREATE)?.let { context.getExternalFilesDir(it) }
        val root = context.getExternalFilesDir(null) ?: return@withContext "no external files folder"
        val lines = mutableListOf<String>()
        val files = root.walkTopDown().onFail { folder, _ -> lines += "${folder.relativeTo(root).invariantSeparatorsPath}/ unreadable" }
        for (file in files) {
            if (file.isFile) lines += "${file.relativeTo(root).invariantSeparatorsPath} ${file.length()} ${sha256(file)}"
        }
        lines.joinToString("\n").ifEmpty { "no files" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Counts the owner's touches that the touch watcher reports for a few seconds, so that spike S3 can be checked with
     * a real finger. Kestrel's own gestures do not count.
     */
    private suspend fun watch(intent: Intent): String {
        val session = graph.screen.value ?: return "Kestrel's accessibility service is off"
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_WATCH_SECONDS).coerceIn(1, MAX_WATCH_SECONDS)
        session.startTouchWatcher()
        try {
            var touches = 0
            withTimeoutOrNull(seconds * 1_000L) { session.ownerTouches.collect { touches++ } }
            return "owner touches in $seconds s: $touches"
        } finally {
            session.stopTouchWatcher()
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
        val COMMANDS = setOf("FILES", "LISTEN", "LISTEN_NOTIFICATION", "LISTEN_AFTER_BOOT", "LOCATION", "SPIKE_RESULTS", "WATCH")
        private const val BUFFER_BYTES = 1 shl 16
        private const val CHANNEL = "spike"
        private const val NOTIFICATION_ID = 7_002
        private const val ACTION_LABEL = "Start listening"
        private const val NOTIFICATION_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_DELAY_SECONDS = 15
        private const val DEFAULT_SECONDS = 3
        private const val DEFAULT_WATCH_SECONDS = 10

        // The hook's answer must come within the receiver's 20 s.
        private const val MAX_WATCH_SECONDS = 15
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_CREATE = "create"
    }
}
