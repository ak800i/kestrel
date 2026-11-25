package dev.kestrel.app.conversation

import dev.kestrel.app.listen.ExitReason
import dev.kestrel.app.listen.ExitRecord
import dev.kestrel.app.listen.ExitRecords
import dev.kestrel.conversation.model.ProcessEnd
import kotlinx.coroutines.CancellationException

/**
 * How Kestrel's previous process ended, for the conversation's restore (plan 7E; plan 7B's `ProcessEnd`), from the newest exit
 * record, as `exitReasonOf` reads it for listening's restore too. Only the owner's sure stop ends a restored goal; a stop
 * that cannot be told from an update on Android 13, any other end, and no record hold it and ask "Continue the goal?".
 */
internal fun processEndOf(record: ExitRecord?): ProcessEnd =
    record?.takeIf { it.reason == ExitReason.OWNER_STOPPED }?.let { ProcessEnd.OwnerStopped(it.timestamp) } ?: ProcessEnd.Other

/** Reads [exits] for the restore. When Android cannot give its record, [report] gets the failure and a goal is held. */
internal suspend fun readProcessEnd(exits: ExitRecords, report: (String, Throwable) -> Unit): ProcessEnd = try {
    processEndOf(exits.newest())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    report("Reading how Kestrel's last process ended", e)
    ProcessEnd.Other
}
