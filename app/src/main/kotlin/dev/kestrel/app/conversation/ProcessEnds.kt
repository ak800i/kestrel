package dev.kestrel.app.conversation

import dev.kestrel.app.listen.ExitReason
import dev.kestrel.app.listen.ExitRecord
import dev.kestrel.conversation.model.ProcessEnd

/**
 * How Kestrel's previous process ended, for the conversation's restore (plan 7E; plan 7B's `ProcessEnd`), from the newest exit
 * record, as `exitReasonOf` reads it for listening's restore too. Only the owner's sure stop ends a restored goal; a stop
 * that cannot be told from an update on Android 13, any other end, and no record (also when Android could not give it,
 * PreviousExit) hold it and ask "Continue the goal?".
 */
internal fun processEndOf(record: ExitRecord?): ProcessEnd =
    record?.takeIf { it.reason == ExitReason.OWNER_STOPPED }?.let { ProcessEnd.OwnerStopped(it.timestamp) } ?: ProcessEnd.Other

