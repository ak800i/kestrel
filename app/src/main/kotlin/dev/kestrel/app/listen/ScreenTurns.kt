package dev.kestrel.app.listen

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Who may act on the screen (spec 7.3; design 5.5 and 7.1 of sub-project 7): one actor at a time. A cancelled instant
 * command's job may still act until it has completed, as when Android finishes a gesture or a binder call of it, so a new
 * instant command, Kestrel's settings and a goal's round wait for it here, for at most [waitMillis]; a job that is still
 * stuck then is reported once to [report], and Kestrel goes on without it. A goal never holds the screen itself, so the
 * instant commands still work during its hand-overs and pauses. Any thread may call it.
 */
internal class ScreenTurns(
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = STOPPING_MILLIS,
) : ScreenTurn {
    private val stopping = AtomicReference<Job?>(null)

    /** The cancelled instant command's [job], which may act on the screen until it has completed. */
    fun stopped(job: Job) {
        stopping.set(job)
    }

    /** Whether a cancelled instant command's job may still act on the screen. */
    fun held(): Boolean = stopping.get()?.isCompleted == false

    /** Returns once no cancelled instant command's job can act on the screen, or once it was stuck for [waitMillis]. */
    override suspend fun take() {
        val job = stopping.get()?.takeIf { !it.isCompleted } ?: return
        if (withTimeoutOrNull(waitMillis) { job.join() } == null) {
            report("A cancelled instant command", StuckCommandException())
            // Kestrel goes on without it: nothing later waits for it again.
            stopping.compareAndSet(job, null)
        }
    }

    override fun toString(): String = "ScreenTurns(held=${held()})"

    companion object {
        /** Section 7.3: how long a new command, a goal or the settings wait for a cancelled command's job. */
        const val STOPPING_MILLIS = 3_000L
    }
}

/** A cancelled instant command's job had not completed after 3 s; Kestrel went on without it. */
private class StuckCommandException : Exception()
