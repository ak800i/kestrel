package dev.kestrel.app.debug.spike

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
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

/** The test-hook commands of spike S1's location check and of spikes S3 and S7. `scripts/hook.ps1` lists them. */
internal class SpikeHooks(private val context: Context, private val graph: AppGraph) {
    suspend fun run(command: String, extras: Bundle): String = when (command) {
        "FILES" -> files(extras)
        "LOCATION" -> location(extras)
        "WATCH" -> watch(extras)
        else -> results(extras)
    }

    private fun location(extras: Bundle): String {
        val source = extras.getString(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = extras.getInt(EXTRA_DELAY, 0)
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
    private suspend fun files(extras: Bundle): String = withContext(Dispatchers.IO) {
        extras.getString(EXTRA_CREATE)?.let { context.getExternalFilesDir(it) }
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
    private suspend fun watch(extras: Bundle): String {
        val session = graph.screen.value ?: return "Kestrel's accessibility service is off"
        val seconds = extras.getInt(EXTRA_SECONDS, DEFAULT_WATCH_SECONDS).coerceIn(1, MAX_WATCH_SECONDS)
        session.startTouchWatcher()
        try {
            var touches = 0
            withTimeoutOrNull(seconds * 1_000L) { session.ownerTouches.collect { touches++ } }
            return "owner touches in $seconds s: $touches"
        } finally {
            session.stopTouchWatcher()
        }
    }

    private fun results(extras: Bundle): String {
        if (extras.getBoolean(EXTRA_CLEAR, false)) {
            SpikeListening.clearResults(context)
            return "cleared"
        }
        return SpikeListening.results(context).ifEmpty { "none yet" }
    }

    companion object {
        val COMMANDS = setOf("FILES", "LOCATION", "SPIKE_RESULTS", "WATCH")
        private const val BUFFER_BYTES = 1 shl 16
        private const val DEFAULT_WATCH_SECONDS = 10

        // The hook's answer must come within the call's 20 s (TestHookProvider).
        private const val MAX_WATCH_SECONDS = 15
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_CREATE = "create"
    }
}
