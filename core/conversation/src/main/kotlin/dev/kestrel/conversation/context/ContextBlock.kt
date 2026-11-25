package dev.kestrel.conversation.context

import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.loop.Redacted
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.ModelInfo
import dev.kestrel.conversation.model.CommandRecord
import java.time.ZoneId

/**
 * The records block of a later command (design 8.3 and 8.4 of sub-project 7), which goes into the request's context after
 * the test-mode note as `TaskRequest.conversation`. It starts with plan 7A's header and a continued conversation's note,
 * and holds the newest earlier record in full, within the caps below, and the older ones condensed. Every text in it was
 * redacted once, when it entered its record, so the block is Kestrel's own text and is redacted nothing twice.
 *
 * The block never passes its budget, counted as `ScreenText.tokens` counts. It is filled in this order, each part only
 * while the block stays within the budget: (1) the newest record's head, request, answer, end, apps and its checklist's
 * line of counts, the request and the answer cut further when a small local budget needs it; (2) its open items in the
 * checklist's order, the rest counted; (3) its data notes, then its lines, then its ledger lines, each newest first, the
 * rest counted; (4) the older records, condensed, newest first, each whole, the rest left out with one line.
 */
object ContextBlock {
    const val BUDGET = 2_000
    const val REQUEST_CAP = 400
    const val ANSWER_CAP = 400
    const val DATA = 5
    const val DATA_CAP = 600
    const val LINES = 10
    const val LINE_CAP = 300
    const val LEDGER = 5
    const val LEDGER_CAP = 200

    // The request's and the answer's caps, tried in turn until the newest record's head fits.
    private val HEAD_CAPS = listOf(REQUEST_CAP, 200, 100, 50, 20)

    /** [BUDGET], or an eighth of a local Ollama model's smaller window, so that the block never crowds out the task. */
    fun budget(model: ModelInfo): Int {
        if (!model.localOllama) return BUDGET
        val window = listOfNotNull(model.lightweight.contextWindow, model.versatile.contextWindow).minOrNull() ?: return BUDGET
        return minOf(BUDGET, window / 8)
    }

    /** The block of the conversation's [earlier] records, oldest first; null for a first command without a [note]. */
    fun build(earlier: List<CommandRecord>, budget: Int, zone: ZoneId, note: String? = null): Redacted? {
        if (earlier.isEmpty() && note == null) return null
        val fill = Fill(budget)
        val opening = listOfNotNull(TaskRequest.CONVERSATION_HEADER, note)
        if (!fill.take(opening)) return null
        val newest = earlier.lastOrNull() ?: return Redacted.kestrel(opening.joinToString("\n"))
        val head = HEAD_CAPS.asSequence().map { head(newest, zone, it) }.firstOrNull { fill.take(it) }
            ?: return Redacted.kestrel((opening + listOfNotNull(fill.one(olderLeftOut(earlier.size)))).joinToString("\n"))
        val body = head + openItems(newest, fill) + newestFirst(newest, fill)
        val older = older(earlier.dropLast(1), fill, zone)
        return Redacted.kestrel((opening + older + body).joinToString("\n"))
    }

    private fun head(record: CommandRecord, zone: ZoneId, cap: Int): List<String> = listOfNotNull(
        RecordLines.head(record, zone),
        RecordLines.request(record, cap),
        RecordLines.answer(record, minOf(cap, ANSWER_CAP)),
        RecordLines.end(record),
        RecordLines.apps(record),
        record.checklist?.let(RecordLines::counts),
    )

    private fun openItems(record: CommandRecord, fill: Fill): List<String> {
        val open = record.checklist?.open.orEmpty()
        if (open.isEmpty()) return emptyList()
        val (taken, more) = fill.group(open.map(RecordLines::item), extra = 0, rest = RecordLines::moreItems)
        return taken + listOfNotNull(more)
    }

    // The data notes, then the lines, then the ledger lines, each taken newest first and shown oldest first after the line
    // that counts the ones left out.
    private fun newestFirst(record: CommandRecord, fill: Fill): List<String> {
        val data = record.data.takeLast(DATA)
        val lines = record.lines.takeLast(LINES)
        val ledger = record.ledger.takeLast(LEDGER)
        return part(data.map { RecordLines.data(it, DATA_CAP) }, record.data.size - data.size + record.droppedData, fill) {
            RecordLines.leftOut(it, "data note", "data notes")
        } + part(lines.map { RecordLines.line(it, LINE_CAP) }, record.lines.size - lines.size + record.droppedLines, fill) {
            RecordLines.leftOut(it, "line", "lines")
        } + part(ledger.map { RecordLines.did(it, LEDGER_CAP) }, record.ledger.size - ledger.size, fill) {
            RecordLines.leftOut(it, "ledger line", "ledger lines")
        }
    }

    private fun part(oldestFirst: List<String>, extra: Int, fill: Fill, rest: (Int) -> String): List<String> {
        val (taken, more) = fill.group(oldestFirst.reversed(), extra, rest)
        return listOfNotNull(more) + taken.reversed()
    }

    // The older records, condensed, newest first and each whole; the line that names the ones left out comes first.
    private fun older(older: List<CommandRecord>, fill: Fill, zone: ZoneId): List<String> {
        val shown = mutableListOf<List<String>>()
        for ((index, record) in older.asReversed().withIndex()) {
            val lines = RecordCondenser.lines(record, zone)
            val left = older.size - index - 1
            if (!fill.take(lines, reserve = if (left > 0) olderLeftOut(left) else null)) break
            shown += lines
        }
        val left = older.size - shown.size
        return listOfNotNull(if (left > 0) fill.one(olderLeftOut(left)) else null) + shown.asReversed().flatten()
    }

    private fun olderLeftOut(count: Int): String =
        "($count earlier ${if (count == 1) "command of this conversation is" else "commands of this conversation are"} left out; " +
            "search_conversations finds them.)"

    // The block's budget as it fills. Each line costs the tokens of itself and its line break, so the joined block never
    // costs more than the lines taken.
    private class Fill(private val limit: Int) {
        private var used = 0

        fun cost(line: String): Int = ScreenText.tokens(line + "\n")

        /** Takes all of [lines] when they fit with room left for [reserve], else nothing. */
        fun take(lines: List<String>, reserve: String? = null): Boolean {
            val cost = lines.sumOf(::cost)
            if (used + cost + (reserve?.let(::cost) ?: 0) > limit) return false
            used += cost
            return true
        }

        fun one(line: String): String? = line.takeIf { take(listOf(it)) }

        /** Takes [items] in order while they fit with room for the line that counts the rest (and [extra] more). */
        fun group(items: List<String>, extra: Int, rest: (Int) -> String): Pair<List<String>, String?> {
            val taken = mutableListOf<String>()
            for ((index, item) in items.withIndex()) {
                val left = items.size - index - 1 + extra
                if (!take(listOf(item), reserve = if (left > 0) rest(left) else null)) break
                taken += item
            }
            val left = items.size - taken.size + extra
            return taken to (if (left > 0) one(rest(left)) else null)
        }
    }
}
