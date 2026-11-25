package dev.kestrel.app.debug

import java.util.concurrent.atomic.AtomicReference

/** How many audio-focus requests the hook sent ([requests]) and how many answered ([answered]), and the newest answer. */
internal data class FocusRecord(val requests: Int, val answered: Int, val last: FocusResult?)

/**
 * The audio-focus requests of goal-control in this process (plan 7E, Task 6's review M3). A request counts when the hook
 * sends it and again when it answers, so a check that waits until both counts reach the requests that it sent reads
 * that request's own result, never the previous one's, also when the two results are the same. A request that failed
 * never answers.
 */
internal class FocusRequests {
    private val record = AtomicReference(FocusRecord(0, 0, null))

    fun asked() {
        record.updateAndGet { it.copy(requests = it.requests + 1) }
    }

    fun answered(result: FocusResult) {
        record.updateAndGet { it.copy(answered = it.answered + 1, last = result) }
    }

    fun now(): FocusRecord = record.get()
}
