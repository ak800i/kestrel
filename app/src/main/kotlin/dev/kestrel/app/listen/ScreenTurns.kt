package dev.kestrel.app.listen

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Who may act on the screen (spec 7.3; design 6.5 and 7.1 of sub-project 7): one actor at a time, and this is the one rule
 * for it. The instant command that the Orchestrator started holds the screen from its start until its job has really
 * ended ([started]); a cancelled job ([stopped]) may still act until it has completed, as when Android finishes a gesture
 * or a binder call of it. A new instant command, Kestrel's settings and every screen step of a goal's round (its start and
 * the end of each of its waits for the owner, `GoalRunner`) wait for that job here. A round that acts for an input of the
 * owner's that came after the command started asks the command to let go: that input chose the goal, and the Orchestrator
 * cancels the command (`Event.ScreenWanted`). A round that goes on by itself, or for an older input, never does: the
 * command is the owner's newest act, as during a hand-over (D15), so the round waits until it has ended. A cancelled job
 * that is still not done after [waitMillis] is reported once to [report], and Kestrel goes on without it. A goal never
 * holds the screen itself, so the instant commands still work during its hand-overs and pauses. Any thread may call it.
 */
internal class ScreenTurns(
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = STOPPING_MILLIS,
) : ScreenTurn {
    /** The command's [job], the newest input number at its start ([since]), how to ask it to let go, and its cancellation. */
    private class Holder(val job: Job, val since: Long, val letGo: () -> Unit) {
        val stopping = CompletableDeferred<Unit>()
    }

    private val holder = AtomicReference<Holder?>(null)

    /**
     * The instant command whose [job] has just started holds the screen until the job has completed, also after it was
     * cancelled. [since] is the newest number of the owner's inputs at its start (`InputIds.newest`); [letGo] asks the
     * Orchestrator to cancel it when an input of the owner's that came after it lets a round act.
     */
    fun started(job: Job, since: Long, letGo: () -> Unit) {
        holder.set(Holder(job, since, letGo))
    }

    /** The command whose [job] it is was cancelled: from now on it gets at most [waitMillis] to complete. */
    fun stopped(job: Job) {
        holder.get()?.takeIf { it.job === job }?.stopping?.complete(Unit)
    }

    /** Whether an instant command's job, running or cancelled, may still act on the screen. */
    fun held(): Boolean = holder.get()?.job?.isCompleted == false

    /**
     * Returns once no instant command's job can act on the screen, or once a cancelled one was stuck for [waitMillis]; true
     * when it waited, because the screen may have changed meanwhile. [after] is the number of the owner's input that lets
     * the caller act now, or null when it goes on by itself.
     */
    override suspend fun take(after: Long?): Boolean {
        val held = holder.get()?.takeIf { !it.job.isCompleted } ?: return false
        if (!held.stopping.isCompleted) {
            if (after != null && after > held.since) {
                held.letGo()
            } else {
                // The owner's command goes on: the round waits until it ends, or until something cancels it.
                select {
                    held.job.onJoin {}
                    held.stopping.onAwait {}
                }
            }
        }
        if (withTimeoutOrNull(waitMillis) { held.job.join() } == null) {
            report("A cancelled instant command", StuckCommandException())
            // Kestrel goes on without it: nothing later waits for it again.
            holder.compareAndSet(held, null)
        }
        return true
    }

    override fun toString(): String = "ScreenTurns(held=${held()})"

    companion object {
        /** Section 7.3: how long a new command, a goal or the settings wait for a cancelled command's job to complete. */
        const val STOPPING_MILLIS = 3_000L
    }
}

/** An instant command's job had not completed 3 s after it was asked to let go of the screen; Kestrel went on without it. */
private class StuckCommandException : Exception()
