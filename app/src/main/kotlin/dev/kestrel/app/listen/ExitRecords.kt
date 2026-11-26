package dev.kestrel.app.listen

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a process of Kestrel's ended, as far as the restore needs to know (design spec, section 8.5). */
internal enum class ExitReason {
    /**
     * The owner stopped Kestrel: a force stop, "Stop app", Stop in "Active apps" or removing it from Recents (prototype P2,
     * and the documentation of Android's Task Manager). Listening stays off, and a restored goal ends as "stop" ends it
     * (plan 7E).
     */
    OWNER_STOPPED,

    /**
     * On Android 13, a stop within [UPDATE_MARGIN_MILLIS] of Kestrel's last install or update, before or after it, which
     * Android 13 records with the same reason as an update (plan 7E). Listening stays off, as after the owner's stop, and a
     * restored goal is held and asks "Continue the goal?".
     */
    UNCLEAR_STOP,

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
 * Reads [exits] once at the start of Kestrel's process, for the conversation's restore and the check of the accessibility
 * service (plan 7E). When Android cannot give its record, [report] gets the failure and the start goes on as without a
 * record.
 */
internal suspend fun readNewestExit(exits: ExitRecords, report: (String, Throwable) -> Unit): ExitRecord? = try {
    exits.newest()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    report("Reading how Kestrel's last process ended", e)
    null
}

/**
 * Reads `ActivityManager.getHistoricalProcessExitReasons()` on [Dispatchers.IO], with the time of Kestrel's last install or
 * update, which tells the owner's stop from an update on Android 13. It reads only the reason and the times, never the
 * record's description, which can hold the text of an exception (section 13.5). The app graph builds one, which listening's
 * restore and the conversation's restore both read (plan 7E).
 */
internal class AndroidExitRecords(private val context: Context) : ExitRecords {
    override suspend fun newest(): ExitRecord? = withContext(Dispatchers.IO) {
        val record = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?: return@withContext null
        val updated = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
        ExitRecord(exitReasonOf(record.reason, record.timestamp, Build.VERSION.SDK_INT, updated), record.timestamp)
    }
}

/** How near Kestrel's last install or update, before or after it, a stop on Android 13 cannot be told from the update. */
internal const val UPDATE_MARGIN_MILLIS = 60_000L

/**
 * What an [ApplicationExitInfo]'s reason [reason], one of its `REASON_*` values, at [timestamp] means for the restores
 * (section 8.5; plan 7E), on Android [sdk], with the time of Kestrel's last install or update [updatedAt]. From Android 14
 * an update has a reason of its own. Android 13 gives an update REASON_USER_REQUESTED, and takes the update's time a few
 * seconds before or after its kill, so there a stop within [UPDATE_MARGIN_MILLIS] of the update, on either side, is
 * [ExitReason.UNCLEAR_STOP].
 */
internal fun exitReasonOf(reason: Int, timestamp: Long, sdk: Int, updatedAt: Long): ExitReason = when (reason) {
    // A force stop, "Stop app", Stop in "Active apps" and removing Kestrel from Recents (prototype P2). REASON_USER_STOPPED
    // is not the owner's: Android stopped the user profile that the process ran in.
    ApplicationExitInfo.REASON_USER_REQUESTED ->
        if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && abs(updatedAt - timestamp) <= UPDATE_MARGIN_MILLIS) {
            ExitReason.UNCLEAR_STOP
        } else {
            ExitReason.OWNER_STOPPED
        }
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    -> ExitReason.CRASH
    else -> ExitReason.OTHER
}
