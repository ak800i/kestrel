package dev.kestrel.conversation.engine

import java.time.ZoneId

/**
 * The conversation's clocks and zone (design 6.2 and 6.7 of sub-project 7). Stored times are wall-clock times
 * ([wallMillis]), so that a restart can tell whether a conversation is still open. The machine's timers (the quiet time,
 * a held goal's 30 minutes, a hand-over's 3 minutes and the typed waits) are deadlines on [sinceBootMillis], which counts
 * the time that the phone sleeps, as a coroutine's `delay` does not; the engine reads them at every input and signal, and
 * a `delay` only wakes it (review of plan 7B's Task 10: I4). `:app`'s `AndroidConversationClock` (plan 7C) reads
 * `System.currentTimeMillis()`, `SystemClock.elapsedRealtime()` and the phone's zone.
 */
interface ConversationClock {
    fun wallMillis(): Long

    /** Milliseconds since the phone started, deep sleep included; it never goes back while the phone runs. */
    fun sinceBootMillis(): Long

    fun zone(): ZoneId
}
