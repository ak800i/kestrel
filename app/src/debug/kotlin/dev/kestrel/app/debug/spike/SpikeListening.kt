package dev.kestrel.app.debug.spike

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Spike S1's location check (design spec, section 19), in debug builds only: can Kestrel read the location while another
 * app is in front? `scripts/spike-s1.ps1` runs it. The results go to a file that `scripts/hook.ps1 spike-results` reads,
 * and they say only whether a location came, never where the device is. `scripts/listen-check.ps1` checks how the real
 * ListeningService starts.
 */
internal object SpikeListening {
    fun record(context: Context, line: String) = synchronized(this) {
        resultsFile(context).appendText("${Instant.now()} $line\n")
    }

    fun results(context: Context): String = synchronized(this) {
        resultsFile(context).takeIf(File::exists)?.readText().orEmpty()
    }

    fun clearResults(context: Context) = synchronized(this) { resultsFile(context).delete() }

    private fun resultsFile(context: Context) = File(context.noBackupFilesDir, "spike-results.txt")
}
