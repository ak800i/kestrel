package dev.kestrel.eval.run

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.RunValues
import dev.kestrel.eval.task.Setup
import dev.kestrel.eval.task.SetupResult
import dev.kestrel.eval.task.Task
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a command asks of its runs: live with the owner's default profile or with the scripted model, a model, and a first name. */
data class RunOptions(val live: Boolean, val model: String? = null, val name: String? = null)

/** A run's restores did not all finish, so the device is not as it was, and the command stops. */
class RestoreFailed(message: String) : Exception(message)

/**
 * One run of a task, and the runs of a task (design D5 of sub-project 6), in task-check.ps1's order: the call state, the
 * device rule, a conversation of the run's own and the setup (whose failure fails only this run, `reason=setup`), the
 * scripted model of a run without a model, the task's turns ([TurnRunner]; design 14.4 of sub-project 7), the checks, and
 * the end however the run ends: the run's restores (the stop of a command whose end the run did not read, the stop of a
 * goal that the run left open, the scripted model removed, and the setup's restores, in that order), the call state
 * again, the credits and the line. The stops and the removal are restores, so that the shutdown hook runs them after
 * Ctrl+C too, as task-check.ps1's `finally` blocks did. [listening] waits until Kestrel listens, before each run. Once the
 * shutdown hook began ([Restores.stopping]), no run and no command starts: the runner throws [Stopping].
 */
class Runner(
    private val device: Device,
    private val hooks: Hooks,
    private val restores: Restores,
    private val clock: Clock,
    private val console: Console,
    private val config: LocalConfig,
    private val work: Path,
    private val writer: LineWriter,
    private val options: RunOptions,
    private val listening: () -> Unit,
    private val markers: Markers = Markers(),
    private val tags: () -> String = { "t" + UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    private val calls = CallGuard(device, console, clock)
    private val scripts = ScriptInstaller(device, hooks, work)
    private val conversations = ConversationGuard(hooks, clock)

    // The run that is under way, which the bottom restore of runAll reads for its credits when Ctrl+C ends it.
    @Volatile
    private var current: Pair<Int, RunState>? = null

    /**
     * Runs [task] [runs] times, a known gap once, with the scripted model's [script] when the runs are not live, and
     * returns how many runs failed. Its bottom restore writes `result=fail reason=stopped` for each run without a line, and
     * the run's detail line with it (with what the run under way knew and what its restores put back), so
     * that an error or Ctrl+C never leaves a run out of the results: the run that was under way with its own credits (after
     * its stop, which runs first: `unknown` for a live task without credits, or when they cannot be read), the runs that
     * never started with 0.00, as task-check.ps1's `Close-TaskRun` wrote them. A resumed suite runs only the runs from
     * [first] on, whose earlier runs have their lines in its folder.
     */
    fun runAll(task: Task, script: String?, runs: Int, first: Int = 1): Int {
        require(options.live || script != null) { "A run without a model needs the scripted model's script." }
        val count = task.runsAtMost?.let { minOf(it, runs) } ?: runs
        if (count < runs) console.warn("Task ${task.name} is a known gap of v1, for ${task.gap}: it runs once.")
        val mark = restores.mark()
        restores.push("the lines of ${task.name}'s runs that did not end", "None: the results lack these runs.") {
            val running = current
            for (number in first..count) {
                if (writer.wrote(task.name, number)) continue
                // The run under way keeps what it knew, and what its restores, which ran first, put back.
                val under = running?.takeIf { it.first == number }?.second
                val spent = under?.let {
                    try {
                        credits(it)
                    } catch (e: Exception) {
                        "unknown"
                    }
                }
                val line = RunLine(task.name, number, RunResult.FAIL, null, CheckResult.SKIPPED, Reason.STOPPED, spent)
                writer.writeOnce(task.name, number, line, under?.detail(task.name, number) ?: RunDetail.empty(task.name, number))
            }
        }
        var failures = 0
        try {
            val used = mutableListOf<String>()
            for (number in first..count) {
                stopIfStopping()
                listening()
                val marker = markers.next(used).also { used.add(it) }
                if (run(task, script, number, marker).result == RunResult.FAIL) failures++
            }
        } finally {
            restores.report(restores.runDownTo(mark))
        }
        return failures
    }

    /**
     * Run [number] of [task] with [marker]; writes its line and its detail line, unless the shutdown hook already wrote
     * them after Ctrl+C, and returns the run line.
     */
    fun run(task: Task, script: String?, number: Int, marker: String): RunLine {
        val scope = RunScope(device, hooks, restores, clock, console, config, work, RunValues(marker, name = options.name))
        val state = RunState(scope.putBack)
        current = number to state
        val mark = restores.mark()
        var verdict: Verdict? = null
        var error: Throwable? = null
        try {
            verdict = attempt(task, script, number, scope, state)
        } catch (e: Throwable) {
            error = e
        }
        val end = close(task, number, state, mark)
        val call = state.call || end.after
        if (end.after) console.warn("Task ${task.name}, run $number: a call showed after the run; :tools:eval ended it with the end-call key.")
        val judged = verdict ?: Verdict(RunResult.FAIL, CheckResult.SKIPPED, Reason.STOPPED)
        val reason = if (call) Reason.CALL else judged.reason
        val line = RunLine(
            task.name, number, if (call) RunResult.FAIL else judged.result, state.answer, judged.check,
            reason, end.credits, asked = state.cancelled().joinToString(",").takeIf { reason == Reason.ASKED },
        )
        writer.writeOnce(task.name, number, line, state.detail(task.name, number))
        current = null
        restores.report(end.failures)
        error?.let { throw it }
        if (end.failures.isNotEmpty()) throw RestoreFailed("The restores of ${task.name}, run $number, did not all finish; check the device by hand.")
        return line
    }

    private fun attempt(task: Task, script: String?, number: Int, scope: RunScope, state: RunState): Verdict {
        stopIfStopping()
        state.call = calls.check()
        if (state.call) {
            console.warn("Task ${task.name}, run $number: a call showed before the run; :tools:eval ended it with the end-call key.")
            return Verdict(RunResult.FAIL, CheckResult.SKIPPED, Reason.CALL)
        }
        if (device.kind !in task.devices) return Verdict(RunResult.SKIPPED, CheckResult.SKIPPED, Reason.UNTESTABLE)
        for (step in listOf<Setup>(NewConversation(conversations)) + task.setup) {
            val result = try {
                step.apply(scope)
            } catch (e: Exception) {
                if (e is DeviceChanged || e is Stopping) throw e
                console.warn("Task ${task.name}, run $number: the setup failed (${e.message ?: e.javaClass.simpleName}); the run fails, and the next run goes on.")
                return Verdict(RunResult.FAIL, CheckResult.SKIPPED, Reason.SETUP)
            }
            if (result is SetupResult.Untestable) {
                console.line("Task ${task.name}, run $number: not testable on ${device.kind.label}: ${result.why}.")
                return Verdict(RunResult.SKIPPED, CheckResult.SKIPPED, Reason.UNTESTABLE)
            }
        }
        if (!options.live) {
            restores.push("the scripted model removed", "Run: scripts\\hook.ps1 -Serial ${device.serial} agent-script -Clear") { scripts.remove() }
            scripts.install(scope.values.fill(script!!, json = true))
        }
        // Design 6.4 of sub-project 7: a goal that the run left open, held or not, would take the next run's command.
        restores.push("the goal of ${task.name}, run $number, ended", "Say 'Kestrel, stop'.") {
            if (!conversations.stopGoal { quietly { hooks.voiceHeard("stop", tags()) } }) {
                console.warn("Task ${task.name}, run $number: Kestrel's goal could not be stopped; say 'Kestrel, stop'.")
            }
        }
        restores.push("the command of ${task.name}, run $number, stopped", "Say 'Kestrel, stop', or turn Kestrel off.") {
            state.current?.let { stop(task, number, it.tag, it.sent) }
        }
        stopIfStopping()
        if (TurnRunner(task, number, scope, state, options, tags, ::stopIfStopping).run() == TurnsEnd.UNDELIVERED) {
            return Verdict(RunResult.FAIL, CheckResult.SKIPPED, Reason.STOPPED)
        }
        val answer = state.answer
        // For the detail line: whether a say text and a data tool's result of the newest command held the run's marker.
        val newest = state.tag
        if (answer != null && newest != null) state.markers = quietly { hooks.agentTask(newest, marker = scope.values.marker) }
        val checked = answer != null && task.check.all { it.passes(scope, answer) }
        if (state.stalled) {
            console.warn("Task ${task.name}, run $number: the device did not answer for ${STALL.inWholeSeconds} s while the task ran, so the run stopped waiting.")
        }
        state.refusal?.let { console.warn("Task ${task.name}, run $number: Kestrel refused the task before it ran ($it).") }
        state.refused?.let {
            console.warn("Task ${task.name}, run $number: Kestrel asked to confirm an action of the category $it, which this task does not name; the run said cancel.")
        }
        val cancelled = state.cancelled()
        if (answer?.get("end") == "CANCELLED" && cancelled.isNotEmpty()) {
            console.warn("Task ${task.name}, run $number: the run's own answer to ${cancelled.joinToString(", ")} ended the task, which ended CANCELLED.")
        }
        return Verdicts.judge(task.verdict, answer, state.timedOut, checked, state.refusal, state.refused, state.stalled, state.declined, cancelled.isNotEmpty())
    }

    /** The shutdown hook began, so no run and no command may start; the hook writes the lines of the runs left. */
    private fun stopIfStopping() {
        if (restores.stopping) throw Stopping("The restores are running because :tools:eval is stopping; no run may start.")
    }

    private fun close(task: Task, number: Int, state: RunState, mark: Int): End {
        var after = calls.check()
        val failures = restores.runDownTo(mark)
        if (failures.isNotEmpty()) console.warn("Task ${task.name}, run $number: the restore did not finish; check the device by hand.")
        if (calls.check()) after = true
        return End(after, credits(state), failures)
    }

    /**
     * Stops the command of [tag] as the owner's "stop" would, only while it runs: a plain "stop" with no command would be an
     * instant command. Once its words were [sent], Kestrel may register the command late, after Ctrl+C, so a tag that
     * Kestrel does not know yet is read once a second for up to 10 s.
     */
    private fun stop(task: Task, number: Int, tag: String, sent: Boolean) {
        if (!running(tag, if (sent) EXISTS_TRIES else 1)) return
        quietly { hooks.voiceHeard("stop", tags()) }
        repeat(STOP_SECONDS) {
            if (quietly { hooks.agentTask(tag) }?.get("state") == "done") return
            clock.sleep(1.seconds)
        }
        console.warn("Task ${task.name}, run $number: the task could not be stopped; say 'Kestrel, stop' or turn Kestrel off.")
    }

    /** Whether the command of [tag] runs, read up to [tries] times while Kestrel does not answer with its state. */
    private fun running(tag: String, tries: Int): Boolean {
        repeat(tries) { attempt ->
            quietly { hooks.agentTask(tag) }?.get("state")?.let { return it == "running" }
            if (attempt < tries - 1) clock.sleep(1.seconds)
        }
        return false
    }

    /**
     * The run's AI Credits (task-check.ps1's `Get-RunCredits`): agent-task's when the run read its commands' ends. A live
     * command whose end the run did not read still sent requests, so agent-task is read once more after its stop, and the
     * credits are "unknown" when that gives none, never 0, so that a ceiling does not miss them. A run that gave no command,
     * or that ran the scripted model, spent 0.00. A run of several commands spent their sum.
     */
    private fun credits(state: RunState): String {
        state.answer?.get("credits")?.let { return it }
        if (!options.live || state.commands.isEmpty()) return "0.00"
        val spent = state.commands.map { command ->
            command.answer?.get("credits") ?: quietly { hooks.agentTask(command.tag) }?.takeIf { it["state"] == "done" }?.get("credits")
        }
        if (spent.any { it == null || !NUMBER.matches(it) }) return "unknown"
        return spent.singleOrNull() ?: String.format(Locale.ROOT, "%.2f", spent.sumOf { it!!.toDouble() })
    }

    private data class End(val after: Boolean, val credits: String, val failures: List<RestoreFailure>)

    companion object {
        val LIMIT = 10.minutes
        val POLL = 2.seconds
        val QUIET = 3.seconds
        val STALL = 30.seconds
        private const val STOP_SECONDS = 15
        internal const val EXISTS_TRIES = 10
        private val NUMBER = Regex("^\\d+(\\.\\d+)?$")
    }
}
