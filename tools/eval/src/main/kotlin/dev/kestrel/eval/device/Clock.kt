package dev.kestrel.eval.device

import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Time for the waits and the polls, and the local time for the results; the tests give a clock that only counts. */
interface Clock {
    /** The time since an arbitrary start, which only grows. */
    fun elapsed(): Duration

    fun sleep(duration: Duration)

    /** This computer's local date and time, for the results files. */
    fun now(): LocalDateTime
}

object SystemClock : Clock {
    private val start = System.nanoTime()

    override fun elapsed(): Duration = (System.nanoTime() - start).nanoseconds

    override fun sleep(duration: Duration) = Thread.sleep(duration.inWholeMilliseconds)

    override fun now(): LocalDateTime = LocalDateTime.now()
}
