package dev.kestrel.app.listen

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a process of Kestrel's ended, as far as the restore needs to know (design spec, section 8.5). */
internal enum class ExitReason {
    /**
     * The owner stopped Kestrel: a force stop, "Stop app", or Stop in "Active apps" (prototype P2). On Android 13, an
     * update of Kestrel gives the same reason.
     */
    OWNER_STOPPED,

    /**
     * Kestrel crashed, in its own code or in a native library, or Android ended it because it stopped answering (an ANR),
     * which a new start soon after a restore could repeat.
     */
    CRASH,

    /** Anything else, such as a low-memory kill or a package update. */
    OTHER,
}

/** The end of a process of Kestrel's: [reason], at [timestamp] in wall-clock milliseconds. */
internal data class ExitRecord(val reason: ExitReason, val timestamp: Long)

/** Reads Android's records of how Kestrel's processes ended. */
internal fun interface ExitRecords {
    /** The newest record, or null when Android has none. A reboot leaves no record of its own. */
    suspend fun newest(): ExitRecord?
}

/**
 * Reads `ActivityManager.getHistoricalProcessExitReasons()` on [Dispatchers.IO]. It reads only the reason and the time,
 * never the record's description, which can hold the text of an exception (section 13.5).
 */
internal class AndroidExitRecords(private val context: Context) : ExitRecords {
    override suspend fun newest(): ExitRecord? = withContext(Dispatchers.IO) {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?.let { ExitRecord(exitReasonOf(it.reason), it.timestamp) }
    }
}

/** What an [ApplicationExitInfo]'s reason [reason], one of its `REASON_*` values, means for the restore (section 8.5). */
internal fun exitReasonOf(reason: Int): ExitReason = when (reason) {
    // A force stop, "Stop app" and Stop in "Active apps" (prototype P2). REASON_USER_STOPPED is not the owner's:
    // Android stopped the user profile that the process ran in.
    ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReason.OWNER_STOPPED
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    -> ExitReason.CRASH
    else -> ExitReason.OTHER
}
