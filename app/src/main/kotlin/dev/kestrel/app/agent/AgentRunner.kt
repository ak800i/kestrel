package dev.kestrel.app.agent

import dev.kestrel.agent.Agent
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskCancellation
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.memory.Recording
import dev.kestrel.agent.memory.RoutineMaker
import dev.kestrel.agent.memory.SaveResult
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Runs Kestrel's tasks (design D2 of sub-project 5; D32 items 1, 2 and 4): it replaces `Listening.runTask`, so [run]
 * returns at once on the controller's thread and the agent runs in [scope], whose failures go to `graph.errors` (D32 item
 * 48). Every task ends with [Event.TaskEnded] and its sentence on every path: a task that cannot start says why ([Prepared.Refused],
 * or "I couldn't start the task." after a failure, which goes to [report] by its classes), and a cancelled one says
 * "Cancelled." with what it had done. The report of a task that "Stop. Kestrel, [task]" replaced is shown on the bar
 * ([showOnBar]), because the Rules speak only the latest task's. The effects that steer a task ([steer]) reach its owner
 * port; [Effect.CancelTask] cancels its job, and a pause that [backstop] reads as a call of the agent's stops it (D13).
 *
 * In a debuggable build ([debuggable]) a task runs only with a model that a debug hook or a device test installed
 * ([modelOverride]), or, with the hook's extra `live`, while a live sitting is open ([liveSession]); otherwise it ends at
 * once, so that no plan step, device test or script can call a provider (design D28). Release builds have no hook, so a
 * task there always uses the default profile.
 */
internal class AgentRunner(
    private val scope: CoroutineScope,
    private val environment: AgentEnvironment,
    private val send: (Event) -> Unit,
    private val latestTask: () -> Int,
    private val options: (Int) -> Map<String, String>,
    private val debuggable: Boolean,
    private val liveSession: suspend () -> Boolean,
    private val deleteHistory: suspend () -> Unit,
    private val showOnBar: (String) -> Unit,
    private val backstop: CallBackstop,
    private val report: (String, Throwable) -> Unit,
    private val nowMillis: () -> Long,
    private val newId: (Int) -> String = ::taskId,
) {
    /**
     * How task number n ended, for the debug hook `agent-task -Result`: its report, the sentence that Kestrel said at its
     * end, the owner's touches that the watcher reported during the task ([touches]) and how many of them were signals
     * without a finger ([signals]: branch B's, or a cancelled gesture of Kestrel's).
     */
    internal data class Ended(val report: TaskReport, val spoken: String, val touches: Int = 0, val signals: Int = 0) {
        override fun toString(): String = "Ended(report=$report, spoken=${spoken.length} characters, touches=$touches, signals=$signals)"
    }

    /** Debug builds only: the model that the hook `agent-script` or a device test installed (design D28). */
    @Volatile
    internal var modelOverride: ModelPort? = null

    /** Debug builds only: the self-chat aliases that the hook `agent-settings` set (design D18); empty otherwise. */
    @Volatile
    internal var selfChatAliases: Map<String, String> = emptyMap()

    private class Running(val task: Int) {
        lateinit var job: Job

        @Volatile
        var owner: TaskOwner? = null

        // Kestrel's own sentence that replaces the report's reason, when the call backstop stopped the task.
        @Volatile
        var stopSentence: String? = null

        // A pause that came before the owner port existed; the owner port gets it as soon as it exists (design D2).
        var pausedEarly = false

        // The owner's touches that the watcher reported during the task, and the signals among them; only the task's touch
        // collector writes them.
        @Volatile
        var touches = 0

        @Volatile
        var signals = 0
    }

    private val lock = Any()
    private var current: Running? = null
    private val ends = LinkedHashMap<Int, Ended>()

    // The steps of the newest task that finished, with the time at which it finished, for "save that as" (design D23).
    @Volatile
    private var kept: Pair<Recording, Long>? = null

    /** Runs task number [task], the owner's words [text]; called on the controller's thread, it returns at once. */
    fun run(task: Int, text: String) = start(task) { running -> runTask(running, text, options(task)) }

    /** Runs Kestrel's own memory [request] as task number [task] (design D2 and D22 of sub-project 5). */
    fun runMemory(task: Int, request: Request.Memory) = start(task) { running -> runLocal(running, request) }

    /** Steers the running task with one of the Orchestrator's effects (design D2 of sub-project 5). */
    fun steer(effect: Effect) {
        val running = synchronized(lock) { current }
        when (effect) {
            Effect.CancelTask -> running?.job?.cancel(TaskCancellation(EndReason.CANCELLED))
            is Effect.ExpirePause -> running?.takeIf { it.task == effect.task }?.job?.cancel(TaskCancellation(EndReason.PAUSED_TOO_LONG))
            is Effect.AnswerTask -> steerOwner(running, effect.task) { it.answered(effect.answer) }
            is Effect.ContinueTask -> steerOwner(running, effect.task) { it.continued() }
            is Effect.ExpireHandOver -> steerOwner(running, effect.task) { it.handOverExpired() }
            is Effect.ResumeTask -> synchronized(lock) { running?.takeIf { it.task == effect.task }?.also { it.pausedEarly = false }?.owner }?.resumed()
            is Effect.PauseTask -> {
                val target = running?.takeIf { it.task == effect.task } ?: return
                if (backstop.onPause(effect.cause) == CallBackstop.Verdict.STOP) {
                    target.stopSentence = AgentTexts.CALL_STARTED
                    target.job.cancel(TaskCancellation(EndReason.CANCELLED))
                    // Ending the call takes binder calls, which must not run on the controller's thread.
                    scope.launch {
                        try {
                            backstop.endCall()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            report("Ending the agent's call", e)
                        }
                    }
                } else {
                    synchronized(lock) { target.owner ?: null.also { target.pausedEarly = true } }?.paused()
                }
            }
            Effect.DeleteHistory -> scope.launch {
                val deleted = try {
                    deleteHistory()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Deleting the history", e)
                    false
                }
                send(Event.HistoryDeleted(deleted))
            }
            else -> Unit
        }
    }

    /** How task number [task] ended, among the newest 16, or null while it runs or when it is older. */
    internal fun ended(task: Int): Ended? = synchronized(lock) { ends[task] }

    /** Whether task number [task] runs now. */
    internal fun running(task: Int): Boolean = synchronized(lock) { current?.task == task }

    /** The kind of the hand-over that task number [task] has open, or null. */
    internal fun handOver(task: Int): HandOverKind? = synchronized(lock) { current?.takeIf { it.task == task } }?.owner?.handOverKind

    /** The category of the confirmation that task number [task] has open, or null (the debug hook `agent-state`). */
    internal fun confirming(task: Int): Category? = synchronized(lock) { current?.takeIf { it.task == task } }?.owner?.confirming

    /** The kind of the question that task number [task] has open, or null (the debug hook `agent-state`). */
    internal fun asking(task: Int): AnswerKind? = synchronized(lock) { current?.takeIf { it.task == task } }?.owner?.asking

    // The Rules keep a task's question or hand-over open until its TaskEnded arrives, so an effect for a task that no
    // longer runs, and whose end this runner never sent, ends it; a second TaskEnded for a task changes nothing.
    private fun steerOwner(running: Running?, task: Int, steer: (TaskOwner) -> Unit) {
        if (running?.task == task) {
            running.owner?.let(steer)
        } else if (synchronized(lock) { task !in ends }) {
            send(Event.TaskEnded(task, reply = null))
        }
    }

    private fun start(task: Int, body: suspend (Running) -> Unit) {
        val running = Running(task)
        val previous = synchronized(lock) { current }
        running.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // The cancelled task before this one ends first, with its report and its release, which stops the touch
                // watcher, so that it never stops the watcher that this task starts (design D19 of sub-project 5).
                withContext(NonCancellable) { previous?.job?.join() }
                body(running)
            } finally {
                synchronized(lock) { if (current === running) current = null }
            }
        }
        synchronized(lock) { current = running }
        running.job.start()
    }

    private suspend fun runTask(running: Running, text: String, options: Map<String, String>) {
        val task = running.task
        var ready: Prepared.Ready? = null
        var spoken = false
        // Design D23: the steps of the last finished task are dropped at the next task.
        kept = null
        try {
            val choice = choose(options) ?: return refuse(task, AgentTexts.couldNotStart(AgentTexts.TEST_BUILD))
            backstop.forget()
            val start = TaskStart(task, newId(task), text, choice, selfChatAliases, backstop::acted)
            val prepared = environment.prepare(start)
            if (prepared is Prepared.Refused) return refuse(task, prepared.sentence)
            val ports = prepared as Prepared.Ready
            ready = ports
            if (synchronized(lock) { running.owner = ports.owner; running.pausedEarly }) ports.owner.paused()
            val offer = !debuggable
            var deferred: Pair<TaskReport, String>? = null
            coroutineScope {
                val touches = launch { ports.touches.collect { touched(running, ports, it) } }
                try {
                    Agent(ports.ports, ports.settings).run(TaskRequest(start.id, text, ports.knownNames)) { result ->
                        val sentence = running.stopSentence?.let { "$it ${AgentTexts.ledger(result)}" }
                            ?: AgentTexts.sentence(result, ports.providerName)
                        result.recording?.let { kept = it to nowMillis() }
                        record(task, Ended(result, sentence, running.touches, running.signals))
                        if (offer && result.offerRoutine && result.recording != null) {
                            deferred = result to sentence
                        } else {
                            spoken = true
                            ended(task, result, sentence)
                        }
                    }
                } finally {
                    touches.cancel()
                }
            }
            deferred?.let { (result, sentence) ->
                send(Event.TaskSays(task, sentence))
                val saved = RoutineMaker(ports.ports.model, ports.owner, ports.ports.memory, Redactor(Vault()), nowMillis = nowMillis)
                    .offer(result.recording!!, start.id)
                spoken = true
                send(Event.TaskEnded(task, (saved as? SaveResult.NotSaved)?.sentence))
            }
        } catch (e: CancellationException) {
            // The agent reported before it rethrew; a cancellation before the agent ran still ends the task.
            if (!spoken && ready == null) withContext(NonCancellable) { refuse(task, AgentTexts.CANCELLED) }
            throw e
        } catch (e: Exception) {
            report("A task", e)
            if (!spoken) refuse(task, AgentTexts.COULD_NOT_START)
        } finally {
            ready?.let { withContext(NonCancellable) { release(it) } }
        }
    }

    // Kestrel's own memory requests ask the owner first and change nothing without his yes (design D2 and D22).
    private suspend fun runLocal(running: Running, request: Request.Memory) {
        val task = running.task
        var ready: Prepared.Ready? = null
        try {
            val choice = if (request is Request.SaveRoutine) choose(emptyMap()) ?: return refuse(task, AgentTexts.couldNotStart(AgentTexts.TEST_BUILD)) else null
            val id = newId(task)
            val prepared = environment.prepare(TaskStart(task, id, "", choice, local = true))
            if (prepared is Prepared.Refused) return refuse(task, prepared.sentence)
            val ports = prepared as Prepared.Ready
            ready = ports
            if (synchronized(lock) { running.owner = ports.owner; running.pausedEarly }) ports.owner.paused()
            val sentence = when (request) {
                is Request.Remember -> remember(ports, request)
                is Request.Forget -> forget(ports, request)
                is Request.SaveRoutine -> save(ports, request, id)
            }
            val result = TaskReport(EndReason.FINISHED)
            record(task, Ended(result, sentence.orEmpty()))
            send(Event.TaskEnded(task, sentence))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { refuse(task, AgentTexts.CANCELLED) }
            throw e
        } catch (e: Exception) {
            report("A memory request", e)
            refuse(task, AgentTexts.COULD_NOT_START)
        } finally {
            ready?.let { withContext(NonCancellable) { release(it) } }
        }
    }

    private suspend fun remember(ready: Prepared.Ready, request: Request.Remember): String {
        if (ready.owner.ask("Remember that ${request.key} is ${request.value}?", AnswerKind.YES_NO) != Answer.Yes) return AgentTexts.NOT_SAVED
        ready.ports.memory.saveFact(Fact(request.key, request.value, FactKind.FACT, nowMillis()))
        return AgentTexts.REMEMBERED
    }

    private suspend fun forget(ready: Prepared.Ready, request: Request.Forget): String {
        val wanted = Normalizer.normalize(request.key)
        val fact = ready.ports.memory.facts().firstOrNull { Normalizer.normalize(it.key) == wanted } ?: return AgentTexts.noFact(request.key)
        if (ready.owner.ask("Forget ${fact.key}?", AnswerKind.YES_NO) != Answer.Yes) return AgentTexts.NOT_SAVED
        ready.ports.memory.deleteFact(fact.key)
        return AgentTexts.FORGOTTEN
    }

    // RoutineMaker says "Saved the routine '<name>'." itself, so a saved routine ends without a sentence of its own.
    private suspend fun save(ready: Prepared.Ready, request: Request.SaveRoutine, id: String): String? {
        val (recording, at) = kept ?: return AgentTexts.NOTHING_TO_SAVE
        if (nowMillis() - at > RECORDING_MILLIS) return AgentTexts.NOTHING_TO_SAVE
        val maker = RoutineMaker(ready.ports.model, ready.owner, ready.ports.memory, Redactor(Vault()), nowMillis = nowMillis)
        return when (val result = maker.save(request.name, recording, id)) {
            is SaveResult.Saved -> null
            is SaveResult.NotSaved -> result.sentence
        }
    }

    private suspend fun choose(options: Map<String, String>): ModelChoice? {
        val override = modelOverride
        val live = options[LIVE] == "true"
        return when {
            !debuggable -> ModelChoice.Profile()
            live && liveSession() -> ModelChoice.Profile(options[MODEL])
            override != null -> ModelChoice.Override(override)
            else -> null
        }
    }

    private suspend fun touched(running: Running, ready: Prepared.Ready, touch: OwnerTouch) {
        running.touches++
        if (touch.signal) running.signals++
        send(Event.OwnerTouched)
        touch.lateEchoMillis?.let { ready.ports.log.write(TaskLog.Kind.ACTION, "possible late echo after $it ms") }
    }

    private fun ended(task: Int, result: TaskReport, sentence: String) {
        if (latestTask() != task) showOnBar(AgentTexts.replaced(result))
        send(Event.TaskEnded(task, sentence))
    }

    private fun refuse(task: Int, sentence: String) {
        record(task, Ended(TaskReport(EndReason.NO_PROFILE), sentence))
        send(Event.TaskEnded(task, sentence))
    }

    private fun record(task: Int, ended: Ended) = synchronized(lock) {
        ends[task] = ended
        while (ends.size > MAX_ENDS) ends.remove(ends.keys.first())
    }

    // Each part in its own try: a failed close must not skip the release, which stops the touch watcher (design D19).
    private suspend fun release(ready: Prepared.Ready) {
        try {
            ready.owner.close()
        } catch (e: Exception) {
            report("Ending a task", e)
        }
        try {
            ready.release()
        } catch (e: Exception) {
            report("Ending a task", e)
        }
    }

    override fun toString(): String = "AgentRunner(running=${current?.task})"

    companion object {
        /** The debug hook's extras (plan 4D's TaskOptions). */
        const val MODEL = "model"
        const val LIVE = "live"

        /** How long a finished task's steps stay for "save that as" (design D23 of sub-project 5). */
        const val RECORDING_MILLIS = 10L * 60 * 1000

        const val MAX_ENDS = 16

        /** `task-<n>-<8 hexadecimal characters>` (design D25 of sub-project 5). */
        fun taskId(task: Int): String = "task-$task-%08x".format(Random.nextInt())
    }
}
