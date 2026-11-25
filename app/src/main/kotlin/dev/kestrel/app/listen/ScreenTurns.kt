package dev.kestrel.app.listen

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who may act on the screen (spec 7.3; design 6.5 and 7.1 of sub-project 7): one actor at a time, and this is the one rule
 * for it, in both directions.
 * - The instant command that the Orchestrator starts holds the screen from its start until its job has really ended
 *   ([startCommand]); a cancelled job ([stopped]) may still act until it has completed, as when Android finishes a gesture
 *   or a binder call of it. A new instant command and Kestrel's settings wait for that job here.
 * - A goal's round holds the screen while it acts: it takes the turn ([take]) at its start and as every wait for the owner
 *   ends, and lets go of it ([release]) as every such wait starts and when it ends, on every path (`GoalRunner`,
 *   `TaskRun.waitForOwner`). While a round holds the turn, no instant command starts ([startCommand] is false) and the
 *   settings do not open ([roundHolds]): one atomic decision, whatever the Orchestrator reads of the goal's phase.
 * - A round that takes the turn while a command holds the screen asks the command to let go only for an input of the
 *   owner's that came after the command started: that input chose the goal, and the Orchestrator cancels the command
 *   (`Event.ScreenWanted`). A round that goes on by itself, or for an older input, never does: the command is the owner's
 *   newest act, as during a hand-over (D15 of sub-project 5), so the round waits until it has ended, and says so as it
 *   starts to wait, for the goal notification (plan 7E). A cancelled job that
 *   is still not done after [waitMillis] is reported once to [report], and Kestrel goes on without it.
 * Any thread may call it.
 */
internal class ScreenTurns(
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = STOPPING_MILLIS,
) : ScreenTurn {
    /** The command's [job], the newest input number at its start ([since]), how to ask it to let go, and its cancellation. */
    private class Holder(val job: Job, val since: Long, val letGo: () -> Unit) {
        val stopping = CompletableDeferred<Unit>()
    }

    private val lock = Any()

    // Guarded by lock.
    private var holder: Holder? = null
    private var round = false

    /**
     * Starts the instant command whose [job] is about to start, unless a goal's round holds the turn: false then, and the
     * job must not start. The command holds the screen until its job has completed, also after it was cancelled. [since] is
     * the newest number of the owner's inputs at its start (`InputIds.newest`); [letGo] asks the Orchestrator to cancel it
     * when an input of the owner's that came after it lets a round act.
     */
    fun startCommand(job: Job, since: Long, letGo: () -> Unit): Boolean = synchronized(lock) {
        if (round) {
            false
        } else {
            holder = Holder(job, since, letGo)
            true
        }
    }

    /** The command whose [job] it is was cancelled: from now on it gets at most [waitMillis] to complete. */
    fun stopped(job: Job) {
        synchronized(lock) { holder }?.takeIf { it.job === job }?.stopping?.complete(Unit)
    }

    /** Whether an instant command's job, running or cancelled, may still act on the screen. */
    fun held(): Boolean = synchronized(lock) { holder }?.job?.isCompleted == false

    /** Whether a goal's round holds the turn, so that nothing else may act on the screen. */
    fun roundHolds(): Boolean = synchronized(lock) { round }

    /**
     * Returns once no instant command's job, running or cancelled, can act on the screen, or once a cancelled one was stuck
     * for [waitMillis]; true when it waited. Listening waits here before a new command or the settings.
     */
    suspend fun commandEnded(): Boolean = waitFor(null) {}

    /**
     * Gives a goal's round the turn once no instant command holds the screen, and holds it until [release]; true when it
     * waited, because the screen may have changed meanwhile. [after] is the number of the owner's input that lets the round
     * act now, or null when it goes on by itself. [waiting] hears it each time the round starts to wait for a command that
     * goes on (plan 7E).
     */
    override suspend fun take(after: Long?, waiting: () -> Unit): Boolean {
        var waited = false
        while (true) {
            synchronized(lock) {
                if (holder?.job?.isCompleted != false) {
                    round = true
                    return waited
                }
            }
            waited = waitFor(after, waiting) || waited
        }
    }

    /** The round lets go of the turn: a wait for the owner starts, or the round has ended. */
    override fun release() {
        synchronized(lock) { round = false }
    }

    private suspend fun waitFor(after: Long?, waiting: () -> Unit): Boolean {
        val held = synchronized(lock) { holder }?.takeIf { !it.job.isCompleted } ?: return false
        if (!held.stopping.isCompleted) {
            if (after != null && after > held.since) {
                held.letGo()
            } else {
                // The owner's command goes on: the caller waits until it ends, or until something cancels it.
                waiting()
                select {
                    held.job.onJoin {}
                    held.stopping.onAwait {}
                }
            }
        }
        if (withTimeoutOrNull(waitMillis) { held.job.join() } == null) {
            report("A cancelled instant command", StuckCommandException())
            // Kestrel goes on without it: nothing later waits for it again.
            synchronized(lock) { if (holder === held) holder = null }
        }
        return true
    }

    override fun toString(): String = "ScreenTurns(held=${held()}, round=${roundHolds()})"

    companion object {
        /** Section 7.3: how long a new command, a goal or the settings wait for a cancelled command's job to complete. */
        const val STOPPING_MILLIS = 3_000L
    }
}

/** An instant command's job had not completed 3 s after it was asked to let go of the screen; Kestrel went on without it. */
private class StuckCommandException : Exception()
