package dev.kestrel.agent.memory

import dev.kestrel.screen.model.Signature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The kinds of a routine's slots (design D23; spec 11.9). */
@Serializable
enum class SlotType { CONTACT, TEXT, PLACE, TIME }

@Serializable
data class Slot(val name: String, val type: SlotType)

/**
 * One step of a recorded task or a routine (design D23): the tool, its target's key, label (as the task's observation
 * showed it: redacted, quoted and cut, which only the routine's request to the model reads) and class, the screen
 * signatures before and after it, the typed text (with placeholders, never a vault value), and the arguments of an
 * `open_*` or data tool. In a routine, `{slot}` markers stand in [text] and in the arguments' strings; [labelSlot] names
 * the slot whose value finds the target (a contact's row) and [titleSlot] the slot that the screen's title after the step
 * must equal (the chat's name). Keys and labels are screen content, so toString prints only kinds (D32 item 42).
 */
@Serializable
data class RoutineStep(
    val tool: String,
    val key: String? = null,
    val label: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val category: String? = null,
    val before: Signature? = null,
    val after: Signature? = null,
    val title: String? = null,
    val text: String? = null,
    val arguments: JsonObject? = null,
    val labelSlot: String? = null,
    val titleSlot: String? = null,
) {
    override fun toString(): String =
        "RoutineStep(tool=$tool, key=${key != null}, text=${text?.length}, labelSlot=${labelSlot != null}, titleSlot=${titleSlot != null})"
}

/**
 * A saved routine (design D23; spec 11.9): three to five [phrasings] with `{slot}` markers, its [slots] and its [steps].
 * It is stored encrypted in `SettingKey` `ROUTINES`. toString prints only counts (D32 item 42).
 */
@Serializable
data class Routine(
    val name: String,
    val phrasings: List<String>,
    val slots: List<Slot>,
    val steps: List<RoutineStep>,
    val created: Long = 0,
) {
    override fun toString(): String =
        "Routine(nameLength=${name.length}, phrasings=${phrasings.size}, slots=${slots.size}, steps=${steps.size})"
}

/**
 * The steps that a task executed (design D23), kept in memory for 10 minutes after a `FINISHED` end so that "Kestrel,
 * save that as <name>" can use it. [request] is the owner's request, redacted; a task that typed a vault value is never
 * recorded. toString prints only counts.
 */
data class Recording(val request: String, val steps: List<RoutineStep>, val signature: String?, val finishedAtMillis: Long) {
    override fun toString(): String = "Recording(requestLength=${request.length}, steps=${steps.size}, signature=${signature != null})"
}
