package dev.kestrel.eval.device

import kotlin.time.Duration

/**
 * The end of a wait, [within] from now on [clock]. A wait measures the time against it, never counts its rounds, so a slow
 * adb cannot stretch it; and a device bounded by it ([Device.until]) gives each command only the time that is [left].
 */
class Deadline(private val clock: Clock, within: Duration) {
    private val end = clock.elapsed() + within

    /** The time until the deadline, zero once it has passed. */
    val left: Duration get() = (end - clock.elapsed()).coerceAtLeast(Duration.ZERO)

    /** Whether the deadline has passed. */
    val passed: Boolean get() = !left.isPositive()

    /** The earlier of this deadline and [other], for a read that must end by both, as a wait's limit and its stall rule. */
    fun earlier(other: Deadline): Deadline = if (other.end < end) other else this
}

/**
 * Tries [done] at once and then [every] apart, until it answers true or [within] has passed, and answers whether it did. A
 * try gets the wait's [Deadline], so that its commands end with it, and no try starts unless a pause of [every] still
 * fits before the deadline: a wait of 10 s, 1 s apart, tries 10 times on a fast device, fewer on a slow one, and ends
 * within the 10 s either way.
 */
fun Clock.waitUntil(within: Duration, every: Duration, done: (Deadline) -> Boolean): Boolean {
    val deadline = Deadline(this, within)
    while (true) {
        if (done(deadline)) return true
        if (deadline.left <= every) return false
        sleep(every)
    }
}
