package dev.kestrel.eval.device

import java.time.LocalDateTime
import kotlin.time.Duration

/** A clock for the tests: it never waits, and each sleep moves its time on and runs [onSleep]. */
class FakeClock(var time: Duration = Duration.ZERO) : Clock {
    val sleeps = mutableListOf<Duration>()
    var onSleep: (Duration) -> Unit = {}

    override fun elapsed(): Duration = time

    override fun sleep(duration: Duration) {
        sleeps.add(duration)
        time += duration
        onSleep(duration)
    }

    override fun now(): LocalDateTime = START.plusNanos(time.inWholeNanoseconds)

    companion object {
        val START: LocalDateTime = LocalDateTime.of(2026, 11, 23, 12, 0, 0)
    }
}
