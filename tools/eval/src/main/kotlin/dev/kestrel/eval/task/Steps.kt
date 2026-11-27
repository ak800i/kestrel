package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Deadline
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.run.Console
import dev.kestrel.eval.run.Restores
import java.net.URLEncoder
import java.nio.file.Path

/**
 * The values of one run (design D4 of sub-project 6) that fill the placeholders of a request and a script: `{marker}`,
 * two words of the run (`{marker_url}` percent-encoded, for a link), and `{name}`, `{time}`, `{number}` and `{address}`, which a setup step sets; plan 6B adds
 * `{date}` (tomorrow as YYYY-MM-DD), `{hour}` and `{minute}` (an alarm's numbers), which a script's data tools need.
 */
class RunValues(
    val marker: String,
    var name: String? = null,
    var time: String? = null,
    var number: String? = null,
    var address: String? = null,
    var date: String? = null,
    var hour: String? = null,
    var minute: String? = null,
) {
    /** [text] with its placeholders filled; for a JSON script ([json]) each value is escaped as a JSON string's content. */
    fun fill(text: String, json: Boolean = false): String = PLACEHOLDER.replace(text) { match ->
        val value = when (match.groupValues[1]) {
            "marker" -> marker
            // The marker inside a link, whose space a link may not hold.
            "marker_url" -> URLEncoder.encode(marker, Charsets.UTF_8).replace("+", "%20")
            "name" -> name
            "time" -> time
            "number" -> number
            "address" -> address
            "date" -> date
            "hour" -> hour
            else -> minute
        } ?: throw IllegalStateException("The run has no value for ${match.value}.")
        if (json) value.replace("\\", "\\\\").replace("\"", "\\\"") else value
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{(marker|marker_url|name|time|number|address|date|hour|minute)\\}")
    }
}

/** The owner's words of a task's turn (design 14.4 of sub-project 7), with the run's placeholders. */
@JvmInline
value class Request(val text: String) {
    fun fill(values: RunValues): String = values.fill(text)
}

/** What one run's steps work with: the device, its hooks, the run's restores, the clock, the console and its values. */
class RunScope(
    val device: Device,
    val hooks: Hooks,
    val restores: Restores,
    val clock: Clock,
    val console: Console,
    val config: LocalConfig,
    /** A folder of this computer for the run's files, such as the filled script and the test photos. */
    val work: Path,
    val values: RunValues,
    /** What a setup read for the run's checks, such as the Wi-Fi page's activity or the alarms before the run. */
    val notes: MutableMap<String, String> = mutableMapOf(),
    /** The settings that a restore found changed by the run and put back, which the run's detail line names (T6). */
    val putBack: PutBack = PutBack(),
) {
    /** The tag of the task that the run gave, once it gave one. */
    var tag: String? = null

    /**
     * This run's scope for a wait that ends at [deadline] (plan 7G), for the checks that the wait reads: its device and hooks
     * give each read only the time left ([Device.until]), and everything else is this scope's own, with the run's tag of
     * now, so that a check reads through it as through the run's scope and no read outlives the wait.
     */
    fun until(deadline: Deadline): RunScope =
        RunScope(device.until(deadline), hooks.until(deadline), restores, clock, console, config, work, values, notes, putBack).also { it.tag = tag }
}

/** What a setup step found: the device is ready, or it cannot run the task ([Untestable], which skips the run). */
sealed interface SetupResult {
    data object Ready : SetupResult

    data class Untestable(val why: String) : SetupResult
}

/** A setup step that could not prepare the device; it fails only this run (`reason=setup`). */
class SetupFailed(message: String) : Exception(message)

/**
 * A step that changes the device for a run (design D4 of sub-project 6). It pushes its restore on the run's
 * [RunScope.restores] before the change, with the value that it read before the change, so that the restore puts back
 * the owner's value, never a default.
 */
interface Setup {
    fun apply(scope: RunScope): SetupResult

    /** The values that the step changes, read now as counts, flags or modes, which a rehearsal compares before and after. */
    fun probe(scope: RunScope): Map<String, String> = emptyMap()
}

/**
 * A setup step whose change lives in Kestrel's process, such as the fixtures' self-chat aliases or the scripted model
 * (design 6.7 of sub-project 7): the process forgets it when it ends, so after a [Turn.Restart] the run makes the change
 * again ([renew]). The restore that [apply] pushed still puts it back after the run.
 */
interface ProcessSetup : Setup {
    /** Makes the step's change again in Kestrel's new process. */
    fun renew(scope: RunScope)
}

/** A check of a run's result on the device (design D4 of sub-project 6), which answers only pass or fail, never screen text. */
interface Check {
    /** [answer] is agent-task's answer at the task's end, or [HookAnswer.EMPTY] in a rehearsal. */
    fun passes(scope: RunScope, answer: HookAnswer): Boolean

    /** Whether the check reads the task's answer or its reply, which only a run with a task has. */
    val needsTask: Boolean get() = false
}
