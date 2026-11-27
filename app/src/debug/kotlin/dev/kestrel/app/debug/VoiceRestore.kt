package dev.kestrel.app.debug

import dev.kestrel.app.listen.CRASH_AFTER_RESTORE_MILLIS

/**
 * The answer of the hook voice-restore (plan 7G): how long ago, in whole seconds, the restore at the start of a process
 * turned Kestrel on, from the stored listening mode's restoredAt, or `none` when no restore's time is stored, and the
 * guard against a crash loop (main spec 8.5), within which a crash keeps Kestrel off. A run that ends Kestrel's process waits
 * until the restore is older than the guard. The times are the wall clock's, as Android's exit records are.
 */
internal object VoiceRestore {
    fun answer(restoredAt: Long?, now: Long): String {
        val ago = restoredAt?.let { ((now - it) / 1_000).toString() } ?: "none"
        return "restoredAgo=$ago guard=${CRASH_AFTER_RESTORE_MILLIS / 1_000}"
    }
}
