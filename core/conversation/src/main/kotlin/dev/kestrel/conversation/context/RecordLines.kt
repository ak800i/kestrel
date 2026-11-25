package dev.kestrel.conversation.context

import dev.kestrel.agent.OwnerLines
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ChecklistRules
import dev.kestrel.agent.goal.ChecklistText
import dev.kestrel.agent.goal.SteerChannel
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
 * in plan 7A's `OwnerLines` ("The owner said:", "The owner added"), which its system prompt counts as his, and every other
 * text as data. Every text of a record, his words and the texts from outside alike (an app's label, a data note with its
 * contacts' names, a line or a ledger line with the screen's labels), is cut at its cap and only then escaped in quotes, by
 * [quoted] or, for his words, by `OwnerLines` in the same way, so that none can close its quote or start a line of its own,
 * such as a forged "The owner said:".
 */
internal object RecordLines {
    const val MAX_APPS = 5

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun head(record: CommandRecord, zone: ZoneId): String {
        val time = TIME.format(Instant.ofEpochMilli(record.atWall).atZone(zone))
        return "Command ${record.number}, at $time (${spokenOrTyped(record.channel)}):"
    }

    fun request(record: CommandRecord, cap: Int): String = "  " + OwnerLines.said(record.request, cap)

    fun answer(record: CommandRecord, cap: Int): String =
        record.answer?.let { "  Kestrel answered: " + quoted(it, cap) } ?: "  Kestrel gave no final answer."

    fun end(record: CommandRecord): String = "  It ended: " + (record.end?.name?.lowercase()?.replace('_', ' ') ?: "not yet") + "."

    /** The last [MAX_APPS] apps that the command used, the latest last (design 8.3). */
    fun apps(record: CommandRecord): String? =
        record.apps.takeLast(MAX_APPS).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "  Apps: ") { quoted(it.label, ScreenText.LABEL_LIMIT) }

    /** The checklist's line of counts, such as "12 done, 1 skipped, 0 failed" (plan 7A's `ChecklistText.closed`). */
    fun counts(checklist: Checklist): String = "  Checklist: ${ChecklistText.closed(checklist)}" + if (checklist.open.isEmpty()) "." else "; open:"

    /** A condensed record's checklist: its progress (plan 7A's `ChecklistText.progress`), which names the open items. */
    fun progress(checklist: Checklist, cap: Int): String = "  Checklist: " + quoted(ChecklistText.progress(checklist), cap)

    fun item(item: ChecklistItem): String = "    ${item.id} [${item.state.wire}] ${quoted(item.text, ChecklistRules.TEXT_LIMIT)}"

    /**
     * A data note as one line: the result's text as the model saw it, with its own quotes, cut at [cap] and only then quoted
     * and escaped, so that a cut inside one of its values never leaves the line's quote open.
     */
    fun data(note: DataNote, cap: Int): String =
        "  Data from ${note.tool} at ${if (note.routine) "routine step" else "step"} ${note.step}: " + quoted(note.text, cap)

    /** A condensed record's line for a data note: how many items the result gave, such as "list_sms gave 5 items". */
    fun gave(note: DataNote): String = "  Data: ${note.tool} gave ${note.items} ${if (note.items == 1) "item" else "items"}."

    fun line(line: RecordLine, cap: Int): String = when (line.kind) {
        LineKind.KESTREL_SAID -> "  Kestrel said: " + quoted(line.text, cap)
        LineKind.KESTREL_ASKED -> "  Kestrel asked: " + quoted(line.text, cap)
        LineKind.OWNER_ANSWERED, LineKind.OWNER_CONTINUED -> "  " + OwnerLines.said(line.text, cap)
        LineKind.OWNER_STEERED -> "  " + OwnerLines.added(steerChannel(line.channel), null, line.text, cap)
        LineKind.NOTE -> "  Note: " + quoted(line.text, cap)
    }

    fun did(line: String, cap: Int): String = "  Kestrel did: " + quoted(line, cap)

    /** "(2 earlier data notes of this command are left out.)", for [one] "data note" and [many] "data notes". */
    fun leftOut(count: Int, one: String, many: String): String =
        "  ($count earlier ${if (count == 1) "$one of this command is" else "$many of this command are"} left out.)"

    /**
     * The one form of a record's text in a line: cut at [cap] with "…", then escaped as screen text is and put in quotes
     * (`ScreenText.quote`). The text was redacted once, when it entered the record (design 8.2).
     */
    private fun quoted(text: String, cap: Int): String = ScreenText.quote(text, cap)

    private fun spokenOrTyped(channel: InputChannel?): String = steerChannel(channel).wire

    private fun steerChannel(channel: InputChannel?): SteerChannel = if (channel == InputChannel.VOICE) SteerChannel.SPOKEN else SteerChannel.TYPED
}
