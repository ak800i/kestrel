package dev.kestrel.conversation.engine

import java.time.ZoneId

/**
 * The conversation's wall clock and zone (design 6.2 and 6.7 of sub-project 7). Stored times are wall-clock times, so that
 * a restart can tell whether a conversation is still open; the timers use the coroutines' `delay`, whose clock is
 * monotonic. `:app`'s `AndroidConversationClock` (plan 7C) reads `System.currentTimeMillis()` and the phone's zone.
 */
interface ConversationClock {
    fun wallMillis(): Long

    fun zone(): ZoneId
}
