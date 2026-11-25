package dev.kestrel.conversation.testing

import dev.kestrel.conversation.engine.ConversationClock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The conversation's clock in the tests: [base] plus what [elapsed] gives, which a coroutine test passes as its virtual
 * time (`{ testScheduler.currentTime }`), so that the wall clock moves with the timers, plus the time that the phone
 * [slept], which both clocks count and no coroutine's `delay` does ([sleep]).
 */
class ManualConversationClock(
    private val elapsed: () -> Long = { 0L },
    var base: Long = BASE,
    var zone: ZoneId = BELGRADE,
) : ConversationClock {
    var slept: Long = 0
        private set

    /** The phone sleeps for [millis]: the clocks move on, and no coroutine's `delay` sees it. */
    fun sleep(millis: Long) {
        slept += millis
    }

    override fun wallMillis(): Long = base + elapsed() + slept

    override fun sinceBootMillis(): Long = elapsed() + slept

    override fun zone(): ZoneId = zone

    override fun toString(): String = "ManualConversationClock"

    companion object {
        val BELGRADE: ZoneId = ZoneId.of("Europe/Belgrade")

        /** Friday, 20 November 2026, 10:20 in Belgrade. */
        val BASE: Long = ZonedDateTime.of(2026, 11, 20, 10, 20, 0, 0, BELGRADE).toInstant().toEpochMilli()
    }
}
