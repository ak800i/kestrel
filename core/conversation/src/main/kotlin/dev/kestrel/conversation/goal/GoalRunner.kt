package dev.kestrel.conversation.goal

import dev.kestrel.agent.Agent
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Ledger
import dev.kestrel.agent.TaskCancellation
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.NoGoal
import dev.kestrel.agent.goal.NoHistory
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.memory.Recording
import dev.kestrel.agent.memory.RoutineMaker
import dev.kestrel.agent.memory.SaveResult
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.context.ContextBlock
import dev.kestrel.conversation.engine.ConversationClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.TaskAllowance
import dev.kestrel.safety.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a goal's rounds (design 7.1 of sub-project 7). Each round waits for the screen's turn, prepares its ports through
 * [environment], builds its request with [GoalRequests], runs `Agent.run` with the conversation's test mode in its
 * settings (design 12.4), and gives its end to the goal's listener exactly once on every path, with Kestrel's sentence: the
 * environment's, or the call backstop's when it stopped the goal. A goal that ended for good names the steering that came
 * too late; a held goal (a pause at a limit, an interruption) keeps it for its next round. A finished goal's recording is
 * kept for "save that as" for [RECORDING_MILLIS], and the routine offer of design D23 of sub-project 5 is made after its
 * report was said, when the environment allows it. [report] gets a failure by its class.
 */
class GoalRunner(
    private val environment: GoalEnvironment,
    private val screen: ScreenTurn,
    private val history: HistorySearch,
    private val clock: ConversationClock,
    private val report: (String, Throwable) -> Unit,
) {
    // The newest finished goal's steps and when it finished, for "save that as" (design D23 of sub-project 5).
    @Volatile
    private var kept: Pair<Recording, Long>? = null

    /** Runs a round of [goal] in [scope]; it returns at once, and the goal's job is set before the round starts. */
    fun run(scope: CoroutineScope, goal: RunningGoal, plan: RoundPlan, owner: OwnerPort) {
        val job = scope.launch(start = CoroutineStart.LAZY) { round(goal, plan, owner) }
        goal.job = job
        job.start()
    }

    /** The end of a held goal, whose round has ended already: [reason] and the partial report of [record] (design 6.4). */
    fun endHeld(command: Int, record: CommandRecord, pending: List<Steer>, reason: EndReason): RoundEnd {
        val result = TaskReport(end = reason, ledger = record.ledger, checklist = record.checklist)
        val answer = environment.sentence(result) + ConversationTexts.tooLate(pending.map { it.text })
        return RoundEnd(command, result, answer, answer)
    }

    /** "Save that as [name]": the newest finished goal's steps as a routine, asked through [owner]; null once saved. */
    suspend fun saveRoutine(name: String, owner: OwnerPort): String? {
        val (recording, at) = kept ?: return ConversationTexts.NOTHING_TO_SAVE
        if (clock.wallMillis() - at > RECORDING_MILLIS) return ConversationTexts.NOTHING_TO_SAVE
        val setup = environment.prepare(RoundStart(0, emptyMap(), owner, NoGoal, NoHistory, RoundPurpose.ROUTINE))
        if (setup is RoundSetup.Refused) return setup.sentence
        val ready = setup as RoundSetup.Ready
        try {
            return when (val saved = maker(ready, owner).save(name, recording, ready.taskId)) {
                is SaveResult.Saved -> null
                is SaveResult.NotSaved -> saved.sentence
            }
        } finally {
            withContext(NonCancellable) { release(ready) }
        }
    }

    private suspend fun round(goal: RunningGoal, plan: RoundPlan, owner: OwnerPort) {
        // Design D23 of sub-project 5: the steps of the newest finished goal are dropped when a new goal starts.
        if (plan.resume == null) kept = null
        var ready: RoundSetup.Ready? = null
        var result: TaskReport? = null
        // The round runs until its agent reports, fails or is cancelled; then its end reaches the listener once, below.
        val cancellation = try {
            screen.take()
            val setup = environment.prepare(RoundStart(goal.command, plan.options, owner, goal, history.port(plan.self), RoundPurpose.GOAL))
            if (setup is RoundSetup.Refused) {
                goal.listener.ended(RoundEnd(goal.command, TaskReport(setup.end), setup.sentence, setup.sentence))
                return
            }
            val prepared = setup as RoundSetup.Ready
            ready = prepared
            goal.knownNames = prepared.knownNames
            if (plan.resume == null) goal.listener.allowed(TaskAllowance.from(plan.text, prepared.knownNames).categories)
            val request = GoalRequests.request(plan, prepared.taskId, prepared.knownNames, ContextBlock.budget(prepared.ports.model.model))
            val settings = prepared.settings.copy(safety = prepared.settings.safety.copy(testMode = plan.testMode))
            coroutineScope {
                val touches = launch { prepared.touches.collect { goal.listener.touched() } }
                try {
                    Agent(prepared.ports, settings).run(request) { result = it }
                } finally {
                    touches.cancel()
                }
            }
            null
        } catch (e: CancellationException) {
            // The agent reported before it rethrew; a cancellation before the agent ran still ends the round.
            if (result == null) result = cancelled(e)
            e
        } catch (e: Exception) {
            report("A goal's round", e)
            null
        }
        val end = result ?: TaskReport(EndReason.KESTREL_ERROR)
        if (cancellation == null) {
            finish(goal, ready, owner, end)
        } else {
            withContext(NonCancellable) { finish(goal, ready, owner, end) }
            throw cancellation
        }
    }

    // The round lets go of the screen before its end reaches the listener (design D19 of sub-project 5): the end can start
    // the next round at once, and a release after it would stop that round's touch watcher. The listener gets the end
    // exactly once, on every path: when Kestrel's words for it fail, ConversationTexts.COULD_NOT stands in for them, and
    // when the routine offer fails or a stop cancels it, the end keeps what was said.
    private suspend fun finish(goal: RunningGoal, ready: RoundSetup.Ready?, owner: OwnerPort, result: TaskReport) {
        ready?.let { withContext(NonCancellable) { release(it) } }
        var end = RoundEnd(goal.command, result, ConversationTexts.COULD_NOT, ConversationTexts.COULD_NOT)
        try {
            val answer = answer(goal, result)
            end = RoundEnd(goal.command, result, answer, answer)
            val recording = result.recording
            if (result.end in HELD || recording == null) return
            kept = recording to clock.wallMillis()
            if (ready == null || !ready.offersRoutines || !result.offerRoutine) return
            // The offer follows the report, so the report is said first and is not said again; the record keeps it as the
            // goal's answer (D3).
            owner.say(answer)
            end = end.copy(reply = null)
            offered(ready, owner, recording)?.let { end = end.copy(reply = it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Ending a goal's round", e)
        } finally {
            goal.listener.ended(end)
        }
    }

    // Kestrel's words for a round's end: the call backstop's when it stopped the goal, else the environment's. A goal that
    // ended for good names the steering that came too late; a held goal keeps it for its next round.
    private fun answer(goal: RunningGoal, result: TaskReport): String {
        val stop = goal.stopSentence
        val sentence = if (stop != null) "$stop ${Ledger.sentence(result.ledger) ?: ConversationTexts.NOTHING_CHANGED}" else environment.sentence(result)
        return if (result.end in HELD) sentence else sentence + ConversationTexts.tooLate(goal.inbox.drain().map { it.text })
    }

    // The routine offer of design D23 of sub-project 5, after the report was said: the sentence that it ends with, if any.
    private suspend fun offered(ready: RoundSetup.Ready, owner: OwnerPort, recording: Recording): String? =
        try {
            (maker(ready, owner).offer(recording, ready.taskId) as? SaveResult.NotSaved)?.sentence
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Offering a routine", e)
            null
        }

    private fun maker(ready: RoundSetup.Ready, owner: OwnerPort) =
        RoutineMaker(ready.ports.model, owner, ready.ports.memory, Redactor(Vault()), nowMillis = clock::wallMillis)

    private fun cancelled(e: CancellationException): TaskReport {
        val cancellation = e as? TaskCancellation
        return TaskReport(end = cancellation?.reason ?: EndReason.CANCELLED, interruption = cancellation?.interruption)
    }

    // A failed release must not hide the round's end, which the listener gets right after it.
    private suspend fun release(ready: RoundSetup.Ready) {
        try {
            ready.release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Ending a goal's round", e)
        }
    }

    override fun toString(): String = "GoalRunner(kept=${kept != null})"

    companion object {
        /** How long a finished goal's steps stay for "save that as" (design D23 of sub-project 5). */
        const val RECORDING_MILLIS = 10L * 60 * 1000

        // The ends after which the goal stays open and held (design 7.4 and D13 of sub-project 7).
        private val HELD = setOf(EndReason.PAUSED_AT_LIMIT, EndReason.INTERRUPTED)
    }
}
