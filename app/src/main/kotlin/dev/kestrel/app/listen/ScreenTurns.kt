package dev.kestrel.app.listen

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Who may act on the screen (spec 7.3; design 6.5 and 7.1 of sub-project 7): one actor at a time, and this is the one rule
 * for it. The instant command that the Orchestrator started holds the screen from its start until its job has really
 * ended ([started]); a cancelled job may still act until it has completed, as when Android finishes a gesture or a binder
 * call of it. A new instant command, Kestrel's settings and every screen action of a goal's round (its start and each
 * gate, `GoalRunner`) wait for that job here. A command that still runs is asked to let go first: the conversation's work
 * goes first, and the Orchestrator cancels the command (`Event.ScreenWanted`). A job that is still not done after
 * [waitMillis] is reported once to [report], and Kestrel goes on without it. A goal never holds the screen itself, so the
 * instant commands still work during its hand-overs and pauses. Any thread may call it.
 */
internal class ScreenTurns(
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = STOPPING_MILLIS,
) : ScreenTurn {
    /** The command's [job], and how to ask its owner to let go of the screen. */
    private class Holder(val job: Job, val letGo: () -> Unit)

    private val holder = AtomicReference<Holder?>(null)

    /**
     * The instant command whose [job] has just started holds the screen until the job has completed, also after it was
     * cancelled; [letGo] asks the Orchestrator to cancel it when someone else needs the screen.
     */
    fun started(job: Job, letGo: () -> Unit) {
        holder.set(Holder(job, letGo))
    }

    /** Whether an instant command's job, running or cancelled, may still act on the screen. */
    fun held(): Boolean = holder.get()?.job?.isCompleted == false

    /**
     * Returns once no instant command's job can act on the screen, or once it was stuck for [waitMillis]; true when it
     * waited for one, because the screen may have changed meanwhile.
     */
    override suspend fun take(): Boolean {
        val held = holder.get()?.takeIf { !it.job.isCompleted } ?: return false
        // A cancelled job is no longer active; a running one is asked to let go.
        if (held.job.isActive) held.letGo()
        if (withTimeoutOrNull(waitMillis) { held.job.join() } == null) {
            report("A cancelled instant command", StuckCommandException())
            // Kestrel goes on without it: nothing later waits for it again.
            holder.compareAndSet(held, null)
        }
        return true
    }

    override fun toString(): String = "ScreenTurns(held=${held()})"

    companion object {
        /** Section 7.3: how long a new command, a goal or the settings wait for an instant command's job to end. */
        const val STOPPING_MILLIS = 3_000L
    }
}

/** An instant command's job had not completed 3 s after it was asked to let go of the screen; Kestrel went on without it. */
private class StuckCommandException : Exception()
