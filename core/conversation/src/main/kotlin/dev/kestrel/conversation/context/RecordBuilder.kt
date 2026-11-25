package dev.kestrel.conversation.context

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import dev.kestrel.safety.Category

/**
 * Builds a command's record as its goal works (design 8.1 of sub-project 7): from the run's traces (plan 7A's
 * `TraceEntry`), which arrive in their record's form except an app's launcher label, which the app chose and which
 * [RecordText.label] redacts and cuts here, and from the conversation's own lines. A question is a line each
 * time that the run put it to the owner, so one that an interruption cut and the run asked again is there twice, and an
 * interruption, a timeout or silence adds no answer line (plan 7A's `TracingOwner`). The ledger's traces are the live
 * view while a round runs; at its end, its report's ledger replaces them ([roundEnded]). A record keeps at most
 * [MAX_LINES] lines and [MAX_DATA] data notes of at most [TEXT_LIMIT] characters each; past that, the oldest go and are
 * counted. These bounds only protect the store; `ContextBlock` decides what reaches a model.
 */
object RecordBuilder {
    const val MAX_LINES = 200
    const val MAX_DATA = 20
    const val TEXT_LIMIT = 2_000

    /** A new record for the owner's [request], which [RecordText] made. */
    fun started(number: Int, atWall: Long, channel: InputChannel, request: String): CommandRecord =
        CommandRecord(number = number, atWall = atWall, channel = channel, request = cut(request))

    /**
     * [record] with the run's [entry]; an answer is the owner's on [channel], his latest input's. A data note's step is a
     * replayed routine's own when [routine], and otherwise the run's step after the goal's [stepsBefore], so that no two
     * notes of a record name the same step (plan 7A numbers a routine's steps, and each run's, from 1).
     */
    fun traced(record: CommandRecord, entry: TraceEntry, channel: InputChannel, routine: Boolean = false, stepsBefore: Int = 0): CommandRecord = when (entry) {
        is TraceEntry.Requested -> record.copy(request = cut(entry.text))
        is TraceEntry.Said -> line(record, LineKind.KESTREL_SAID, entry.text, null)
        is TraceEntry.Asked -> line(record, LineKind.KESTREL_ASKED, entry.text, null)
        is TraceEntry.Answered -> line(record, LineKind.OWNER_ANSWERED, entry.text, channel)
        is TraceEntry.Data ->
            data(record, DataNote(entry.tool, if (routine) entry.step else stepsBefore + entry.step, cut(entry.text), entry.items, routine))
        is TraceEntry.AppUsed -> used(record, AppUse(RecordText.label(entry.label), entry.packageName))
        is TraceEntry.Did -> record.copy(ledger = record.ledger + cut(entry.line))
    }

    /** [record] with one more line; the oldest go past [MAX_LINES]. */
    fun line(record: CommandRecord, kind: LineKind, text: String, channel: InputChannel?): CommandRecord {
        val lines = record.lines + RecordLine(kind, cut(text), channel)
        val over = lines.size - MAX_LINES
        return if (over <= 0) record.copy(lines = lines) else record.copy(lines = lines.drop(over), droppedLines = record.droppedLines + over)
    }

    /** [record] whose request or steering asked for [categories] (D16). */
    fun allowed(record: CommandRecord, categories: Set<Category>): CommandRecord =
        record.copy(allowance = record.allowance + categories.map { it.wire })

    fun checklist(record: CommandRecord, checklist: Checklist): CommandRecord = record.copy(checklist = checklist)

    /**
     * The round that started when [record] held [from] ledger lines ended with [ledger], its report's (plan 7A), which holds
     * every line of the round in its record's form: also those of a step that the round's end cut, and the shares that
     * Kestrel could not see go. They replace the lines that the round's traces added; a line that only the traces hold
     * stays, which happens only when the round's report did not come from its run (a failure before the run reported).
     */
    fun roundEnded(record: CommandRecord, from: Int, ledger: List<String>): CommandRecord {
        val reported = ledger.map(::cut)
        val tracedOnly = record.ledger.drop(from).filterNot { it in reported }
        return record.copy(ledger = record.ledger.take(from) + reported + tracedOnly)
    }

    /** [record] whose goal or request ended with [end] and the final sentence [answer], in its spoken form. */
    fun ended(record: CommandRecord, end: EndReason, answer: String?): CommandRecord = record.copy(end = end, answer = answer?.let(::cut))

    // An app used again moves to the end, so that the apps are in the order of their last use (design 8.3).
    private fun used(record: CommandRecord, app: AppUse): CommandRecord =
        record.copy(apps = record.apps.filterNot { it.packageName == app.packageName } + app)

    private fun data(record: CommandRecord, note: DataNote): CommandRecord {
        val data = record.data + note
        val over = data.size - MAX_DATA
        return if (over <= 0) record.copy(data = data) else record.copy(data = data.drop(over), droppedData = record.droppedData + over)
    }

    private fun cut(text: String): String = ScreenText.cut(text, TEXT_LIMIT)
}
