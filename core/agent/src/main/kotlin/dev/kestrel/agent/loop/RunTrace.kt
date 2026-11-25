package dev.kestrel.agent.loop

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import kotlinx.coroutines.CancellationException

/**
 * What a run tells the goal around it (plan 7A; design 8.1 of sub-project 7), as it happens: the request, the dialogue
 * (through [TracingOwner]), each data tool's result, each app that a step acted in or opened, and each new ledger line.
 * Every text is in its record's form, redacted once and with each vault value as its kind's words, so that no record holds
 * a placeholder (design 7.1). A failure of the goal's store is logged by its class through [log] and never ends the task.
 */
internal class RunTrace(
    private val goal: GoalPort,
    private val apps: AppsPort,
    private val vault: Vault,
    private val redactor: Redactor,
    private val log: suspend (TaskLog.Kind, String) -> Unit,
) {
    // The apps whose use the record holds, and how many of the ledger's lines it holds.
    private val usedApps = mutableSetOf<String>()
    private var tracedLines = 0

    /** Step [step] starts (design 10.5 of sub-project 7). */
    suspend fun stepStarted(step: Int) = guarded { goal.stepStarted(step) }

    /** The owner's request of a run that is no resume, as the task message carries it, redacted once. */
    suspend fun requested(request: Redacted) = traced(TraceEntry.Requested(vault.spoken(request.text)))

    /** What Kestrel said: a `say`, a hand-over's sentence or a permission request's reason. */
    suspend fun said(text: String) = traced(TraceEntry.Said(recordText(text)))

    /** A question or a confirmation that Kestrel asks now. */
    suspend fun asked(text: String) = traced(TraceEntry.Asked(recordText(text)))

    /** The owner's answer: his words, a number, yes or no. */
    suspend fun answered(text: String) = traced(TraceEntry.Answered(recordText(text)))

    /** A data tool's [result] of step [step] as the model saw it, already redacted and quoted. */
    suspend fun data(tool: String, step: Int, result: Redacted) = traced(TraceEntry.Data(tool, step, vault.spoken(result.text)))

    /** An app that an executed step acted in or opened, once per run, by its launcher label. */
    suspend fun acted(packageName: String) {
        if (usedApps.add(packageName)) guarded { goal.traced(TraceEntry.AppUsed(apps.label(packageName) ?: packageName, packageName)) }
    }

    /** The run's [checklist] after an update that the rules accepted (design 7.2 of sub-project 7). */
    suspend fun checklist(checklist: Checklist) = guarded { goal.checklistChanged(checklist) }

    /** The ledger's [lines] that the record does not hold yet; the ledger only grows. */
    suspend fun ledger(lines: List<String>) {
        for (line in lines.drop(tracedLines)) traced(TraceEntry.Did(vault.spoken(line)))
        tracedLines = lines.size
    }

    // [text] in its record's form: redacted once, here, with each vault value as its kind's words.
    private fun recordText(text: String): String = vault.spoken(redactor.redact(text))

    private suspend fun traced(entry: TraceEntry) = guarded { goal.traced(entry) }

    // A failure of the goal's store, or of the launcher's label, never ends the task.
    private suspend fun guarded(report: suspend () -> Unit) {
        try {
            report()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TaskLog.Kind.ERROR, "goal: ${e.javaClass.name}")
        }
    }

    override fun toString(): String = "RunTrace(apps=${usedApps.size}, lines=$tracedLines)"
}
