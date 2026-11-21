package dev.kestrel.agent.memory

import dev.kestrel.agent.loop.Outcome
import dev.kestrel.agent.loop.TaskRun
import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.tools.ParsedCall
import dev.kestrel.agent.tools.ToolArguments
import dev.kestrel.agent.tools.Tools
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.model.ScreenSignature
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Replays a routine without a model (design D23; spec 11.9 and 14.4). Each step reads a fresh snapshot; a step that acts
 * on the screen (a tap, a long press, typing, a scroll, a swipe or back) checks that the screen's signature before it
 * matches the recorded one (the signature has no labels, so a chat with John matches a chat recorded with Ana), while a
 * step that opens an app, a link or a settings page, goes home, waits or calls a data tool runs from any screen, so that
 * a routine that starts by opening its app replays wherever the owner is. A contact or place slot that names an alias
 * takes the alias's saved value. A step finds its element by key, or a slot-dependent target by the slot's value among
 * elements of the recorded class; then the step runs through the agent's own path, with the element check, the safety
 * check, its questions and test mode; and the signature after a step that acts on the screen or opened one, and a
 * slot-dependent title, are checked. A signature
 * leaves out the window's ID, which Android gives each new window, so a routine replays in a later session of the app. A
 * recorded target without a key or a slot (an OCR line) never replays by its old number: the step fails. Each step that
 * ran gets its `ACTION` entry and its ledger line (D24, D25). A mismatch, or a pause that withdrew a step, returns the
 * note for the agent, which goes on from the screen as it is. A touch cancels a replay as it cancels a task.
 */
internal class RoutineReplayer(private val run: TaskRun) {
    /** Null when every step ran; else "Routine '<name>' failed at step <n> of <m>: <what differed>." */
    suspend fun replay(match: RoutineMatch): String? {
        val routine = match.routine
        val total = routine.steps.size
        val values = values(match)
        for ((index, step) in routine.steps.withIndex()) {
            fun failed(what: String) = "Routine '${run.redactor.redact(routine.name)}' failed at step ${index + 1} of $total: $what."
            val observation = run.observe(run.fresh(), last = null, targetKey = null)
            if (step.tool in ON_SCREEN && step.before != null && !step.before.sameScreen(observation.signature)) {
                return failed("the screen before it was not the recorded one")
            }
            val arguments = arguments(step, routine, values, observation.table) ?: return failed("its element was not on the screen")
            val call = (ToolArguments.parse(step.tool, arguments, observation.table, run.vault) as? ParsedCall.Valid)?.call
                ?: return failed("its arguments did not fit the screen")
            val started = run.ports.clock.elapsedMillis()
            val done = run.execute(call)
            run.replayed(done, started)
            if (!done.executed) return failed("the task was paused")
            when (val outcome = done.outcome) {
                is Outcome.Failed, is Outcome.Refused, is Outcome.CheckFailed, Outcome.PermissionRefused -> return failed(outcome.words)
                else -> Unit
            }
            // A step that brought up no screen (a wait, or a data tool that opened nothing) recorded the screen that the owner
            // happened to be on, so only a step that acts on the screen or opened one checks the screen after it; the next
            // step that acts on the screen checks its own screen before it.
            val after = done.after ?: run.fresh()
            val brought = step.tool in ON_SCREEN || (step.tool != Tools.WAIT && done.after != null)
            if (brought && step.after != null && !step.after.sameScreen(ScreenSignature.of(after))) return failed("the screen after it was not the recorded one")
            val titleSlot = step.titleSlot
            if (titleSlot != null) {
                val window = ScreenTree.frontWindow(after) ?: return failed("no app was in front")
                val title = ScreenTitle.of(after, window, run.label(window.packageName), withRule3 = false)
                if (title == null || !names(routine, titleSlot, values[titleSlot].orEmpty(), listOf(title))) {
                    return failed("the screen's title was not the one asked for")
                }
            }
        }
        return null
    }

    // A contact or place slot that names an alias takes the alias's saved value ("my brother = contact John Smith" gives
    // "John Smith", "the office = …" its address), as `find_contact` and `navigate` resolve aliases (D22, D23).
    private fun values(match: RoutineMatch): Map<String, String> = match.values.mapValues { (slot, value) ->
        val type = match.routine.slots.firstOrNull { it.name == slot }?.type
        if (type == SlotType.CONTACT || type == SlotType.PLACE) run.memory.alias(value)?.takeIf { it.isNotBlank() } ?: value else value
    }

    // The recorded arguments with each slot's value, and the target's number in the newest observation.
    private fun arguments(step: RoutineStep, routine: Routine, values: Map<String, String>, table: ObservationTable): JsonObject? {
        val filled = (step.arguments ?: JsonObject(emptyMap())).mapValues { (_, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapValues value
            JsonPrimitive(fill(text, values))
        }.toMutableMap()
        if (step.tool == Tools.TYPE_TEXT && step.text != null) filled["text"] = JsonPrimitive(fill(step.text, values))
        val slot = step.labelSlot
        val entry = when {
            slot != null -> {
                val candidates = table.entries.filter { it.className == step.className && !it.label.isNullOrBlank() }
                candidates.singleOrNull { names(routine, slot, values[slot].orEmpty(), listOfNotNull(it.label)) } ?: return null
            }
            step.key != null -> table.byKey(step.key) ?: return null
            "target" in filled -> return null
            else -> null
        }
        if (entry != null) filled["target"] = JsonPrimitive(entry.number)
        return JsonObject(filled)
    }

    // Whether [value] names [labels]: a contact's first name finds its full name, as "call X" does; other slots match
    // strictly.
    private fun names(routine: Routine, slot: String, value: String, labels: List<String>): Boolean {
        if (routine.slots.firstOrNull { it.name == slot }?.type == SlotType.CONTACT) {
            return ContactMatcher.find(value, listOf(Contact(0, labels, emptyList(), starred = false))) is ContactMatch.One
        }
        return LabelMatcher.decide(value, listOf(MatchCandidate("label", labels)), Strictness.STRICT) is MatchDecision.Found
    }

    private fun fill(text: String, values: Map<String, String>): String =
        values.entries.fold(text) { acc, (slot, value) -> acc.replace("{$slot}", value) }

    override fun toString(): String = "RoutineReplayer"

    private companion object {
        // The steps that act on an element or the content of the screen before them; the others (opening an app, a link
        // or a settings page, going home, waiting, the data tools) run from any screen.
        val ON_SCREEN = Tools.WITH_CATEGORY + Tools.BACK
    }
}
