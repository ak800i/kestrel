package dev.kestrel.app.debug.spike

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Spike S1 (design spec, section 19), in debug builds only: can Kestrel start a microphone foreground service and record
 * when the accessibility service, a notification, the tile or a restart starts it, and can it read the location while
 * another app is in front? `scripts/spike-s1.ps1` runs the checks. The results go to a file that
 * `scripts/hook.ps1 spike-results` reads, and they say only whether each step worked, never what was recorded or where
 * the device is. Sub-project 2 replaces this package with the real ListeningService and tile.
 */
internal object SpikeListening {
    fun start(context: Context, source: String, seconds: Int) {
        try {
            context.startForegroundService(SpikeListeningService.intent(context, source, seconds))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            record(context, "listen source=$source start=not-allowed")
        } catch (e: SecurityException) {
            record(context, "listen source=$source start=SecurityException")
        }
    }

    fun record(context: Context, line: String) = synchronized(this) {
        resultsFile(context).appendText("${Instant.now()} $line\n")
    }

    fun results(context: Context): String = synchronized(this) {
        resultsFile(context).takeIf(File::exists)?.readText().orEmpty()
    }

    fun clearResults(context: Context) = synchronized(this) { resultsFile(context).delete() }

    /** Asks for a recording when Kestrel's process starts next, which a reboot causes. */
    fun requestListenAtNextStart(context: Context) = nextStartFlag(context).writeText("")

    fun takeListenAtNextStart(context: Context): Boolean = nextStartFlag(context).delete()

    private fun resultsFile(context: Context) = File(context.noBackupFilesDir, "spike-results.txt")

    private fun nextStartFlag(context: Context) = File(context.noBackupFilesDir, "spike-listen-at-next-start")
}
