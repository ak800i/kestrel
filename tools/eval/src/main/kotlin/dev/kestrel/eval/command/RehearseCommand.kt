package dev.kestrel.eval.command

import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Markers
import dev.kestrel.eval.run.Stopping
import dev.kestrel.eval.task.Catalogue
import dev.kestrel.eval.task.Rehearsal
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.RunValues
import dev.kestrel.eval.task.Setup
import dev.kestrel.eval.task.SetupResult
import dev.kestrel.eval.task.Task

/**
 * `rehearse --device <s>` (design D7 of sub-project 6): each task's setup, the end state that its rehearsal makes by adb
 * or by the script's own taps, its checks, which must pass, and its restores, and then the values that its steps
 * changed, compared with their values before; without Kestrel's task or a model, so it runs on the A71 too. A task whose
 * check needs Kestrel's task rehearses its setup and restore only. Prints one line per task:
 * `rehearse=<task> result=<pass|fail|skipped> reason=<ok|setup-only|untestable|setup|check|restore|changed>`.
 */
class RehearseCommand(private val environment: Environment, private val command: Command.Rehearse) {
    fun execute(): Int {
        val session = Session(environment, command.device)
        val kind = session.device.kind
        val tasks = Catalogue.ALL.filter { kind in it.devices }
        val unknown = command.tasks.orEmpty().filter { name -> tasks.none { it.name == name } }
        if (unknown.isNotEmpty()) throw Refusal("${kind.label} rehearses no task ${unknown.joinToString()}.")
        val chosen = command.tasks?.let { names -> tasks.filter { it.name in names } } ?: tasks
        return session.guarded {
            session.prepare(chosen, live = false, scripts = emptyList())
            var failed = 0
            for (task in chosen) {
                val (result, reason) = rehearse(session, task)
                if (result == "fail") failed++
                session.console.line("rehearse=${task.name} result=$result reason=$reason")
            }
            if (failed > 0) 1 else 0
        }
    }

    private fun rehearse(session: Session, task: Task): Pair<String, String> {
        val scope = RunScope(
            session.device, session.hooks, session.restores, environment.clock, session.console, session.config, session.work,
            RunValues(Markers().next(emptyList())),
        )
        val mark = session.restores.mark()
        val before = mutableMapOf<String, String>()
        val probed = mutableListOf<Setup>()
        var outcome = "pass" to "ok"
        try {
            for (step in task.setup + (task.rehearsal as? Rehearsal.Steps)?.steps.orEmpty()) {
                // A later step that reads the same value sees the earlier steps' change, so the first read counts.
                step.probe(scope).forEach { (key, value) -> before.putIfAbsent(key, value) }
                probed.add(step)
                val result = step.apply(scope)
                if (result is SetupResult.Untestable) {
                    session.console.line("Rehearsal of ${task.name}: not testable on ${session.device.kind.label}: ${result.why}.")
                    outcome = "skipped" to "untestable"
                    break
                }
            }
            if (outcome.first == "pass") {
                outcome = when (task.rehearsal) {
                    is Rehearsal.SetupOnly -> "pass" to "setup-only"
                    is Rehearsal.Steps -> if (task.check.all { it.passes(scope, HookAnswer.EMPTY) }) "pass" to "ok" else "fail" to "check"
                }
            }
        } catch (e: DeviceChanged) {
            throw e
        } catch (e: Stopping) {
            throw e
        } catch (e: Exception) {
            session.console.warn("Rehearsal of ${task.name}: ${e.message ?: e.javaClass.simpleName}")
            outcome = "fail" to "setup"
        }
        val failures = session.restores.runDownTo(mark)
        session.restores.report(failures)
        if (failures.isNotEmpty()) return "fail" to "restore"
        val after = probed.fold(mutableMapOf<String, String>()) { values, step -> values.apply { putAll(step.probe(scope)) } }
        val changed = before.keys.filter { before[it] != after[it] }
        if (changed.isNotEmpty()) {
            session.console.warn("Rehearsal of ${task.name}: not as before: ${changed.joinToString()}")
            return "fail" to "changed"
        }
        return outcome
    }
}
