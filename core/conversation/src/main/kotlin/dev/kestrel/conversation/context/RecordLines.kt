package dev.kestrel.conversation.context

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ChecklistRules
import dev.kestrel.agent.goal.ChecklistText
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The lines of a record in the records block (design 8.2 and 8.4 of sub-project 7), Kestrel's own text: the owner's words
 * after "The owner said:" and "The owner added", which plan 7A's system prompt counts as his, and every other text in
 * quotes, escaped, so that it stays data. Data notes keep their quotes as the earlier model saw them.
 */
internal object RecordLines {
    const val MAX_APPS = 5

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun head(record: CommandRecord, zone: ZoneId): String {
        val time = TIME.format(Instant.ofEpochMilli(record.atWall).atZone(zone))
        return "Command ${record.number}, at $time (${spokenOrTyped(record.channel)}):"
    }

    fun request(record: CommandRecord, cap: Int): String = "  The owner said: " + ScreenText.quote(record.request, cap)

    fun answer(record: CommandRecord, cap: Int): String =
        record.answer?.let { "  Kestrel answered: " + ScreenText.quote(it, cap) } ?: "  Kestrel gave no final answer."

    fun end(record: CommandRecord): String = "  It ended: " + (record.end?.name?.lowercase()?.replace('_', ' ') ?: "not yet") + "."

    fun apps(record: CommandRecord): String? =
        record.apps.takeLast(MAX_APPS).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "  Apps: ") { it.label }

    /** The checklist's line of counts, such as "12 done, 1 skipped, 0 failed" (plan 7A's `ChecklistText.closed`). */
    fun counts(checklist: Checklist): String = "  Checklist: ${ChecklistText.closed(checklist)}" + if (checklist.open.isEmpty()) "." else "; open:"

    fun item(item: ChecklistItem): String = "    ${item.id} [${item.state.wire}] ${ScreenText.quote(item.text, ChecklistRules.TEXT_LIMIT)}"

    fun moreItems(count: Int): String = "    (and $count more open ${if (count == 1) "item" else "items"})"

    fun data(note: DataNote, cap: Int): String =
        "  Data from ${note.tool} at ${if (note.routine) "routine step" else "step"} ${note.step}: " + ScreenText.cut(note.text, cap).replace("\n", "\n    ")

    fun line(line: RecordLine, cap: Int): String = when (line.kind) {
        LineKind.KESTREL_SAID -> "  Kestrel said: " + ScreenText.quote(line.text, cap)
        LineKind.KESTREL_ASKED -> "  Kestrel asked: " + ScreenText.quote(line.text, cap)
        LineKind.OWNER_ANSWERED -> "  The owner said: " + ScreenText.quote(line.text, cap)
        LineKind.OWNER_STEERED -> "  The owner added (${spokenOrTyped(line.channel)}): " + ScreenText.quote(line.text, cap)
        LineKind.OWNER_CONTINUED -> "  The owner said to go on."
        LineKind.NOTE -> "  Note: " + ScreenText.quote(line.text, cap)
    }

    fun did(line: String, cap: Int): String = "  Kestrel did: " + ScreenText.quote(line, cap)

    /** "(2 earlier data notes of this command are left out.)", for [one] "data note" and [many] "data notes". */
    fun leftOut(count: Int, one: String, many: String): String =
        "  ($count earlier ${if (count == 1) "$one of this command is" else "$many of this command are"} left out.)"

    private fun spokenOrTyped(channel: InputChannel?): String = if (channel == InputChannel.VOICE) "spoken" else "typed"
}
