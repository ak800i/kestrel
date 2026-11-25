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
 * redacted once, when it entered its record, so the block is Kestrel's own text and is never redacted twice.
 *
 * The block never passes its budget, counted as `ScreenText.tokens` counts, and whatever it leaves out, one line counts.
 * Before anything else, it keeps room for the line that each part would need if it left out all it has; each part then
 * gives that room back, shows what fits and counts the rest in the room it kept, so a count line is never the line that
 * does not fit. The parts are filled in this order, each only while the block stays within the budget: (1) the newest
 * record's head, request, answer, end, apps and its checklist's line of counts, the request and the answer cut further
 * when a small local budget needs it; (2) its open items in the checklist's order; (3) its data notes, then its lines,
 * then its ledger lines, each newest first; (4) the older records, condensed, newest first, each whole. When even the
 * smallest head does not fit beside the kept room, one line counts every record instead.
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

    /**
     * The block of the conversation's [earlier] records, oldest first. Null for a first command without a [note], and
     * when the budget cannot hold the header, the note and the line that counts every record.
     */
    fun build(earlier: List<CommandRecord>, budget: Int, zone: ZoneId, note: String? = null): Redacted? {
        if (earlier.isEmpty() && note == null) return null
        val fill = Fill(budget)
        val opening = listOfNotNull(TaskRequest.CONVERSATION_HEADER, note)
        val everyRecord = earlier.takeIf { it.isNotEmpty() }?.let { olderLeftOut(it.size) }
        if (!fill.take(opening, reserve = listOfNotNull(everyRecord))) return null
        val newest = earlier.lastOrNull() ?: return block(opening)

        val items = Part(newest.checklist?.open.orEmpty().map { listOf(RecordLines.item(it)) }, ::openLeftOut)
        val data = newestFirst(newest.data.map { RecordLines.data(it, DATA_CAP) }, DATA, newest.droppedData) {
            RecordLines.leftOut(it, "data note", "data notes")
        }
        val lines = newestFirst(newest.lines.map { RecordLines.line(it, LINE_CAP) }, LINES, newest.droppedLines) {
            RecordLines.leftOut(it, "line", "lines")
        }
        val ledger = newestFirst(newest.ledger.map { RecordLines.did(it, LEDGER_CAP) }, LEDGER, 0) {
            RecordLines.leftOut(it, "ledger line", "ledger lines")
        }
        val older = Part(earlier.dropLast(1).asReversed().map { RecordCondenser.lines(it, zone) }, ::olderLeftOut)
        val parts = listOf(items, data, lines, ledger, older)

        val head = if (fill.hold(parts)) HEAD_CAPS.asSequence().map { head(newest, zone, it) }.firstOrNull { fill.take(it) } else null
        if (head == null) return block(opening + listOfNotNull(everyRecord))
        val shownItems = fill.fill(items)
        val shownData = fill.fill(data)
        val shownLines = fill.fill(lines)
        val shownLedger = fill.fill(ledger)
        val shownOlder = fill.fill(older)
        return block(
            opening + shownOlder.countFirst() + head + shownItems.countLast() + shownData.countFirst() + shownLines.countFirst() +
                shownLedger.countFirst(),
        )
    }

    private fun block(lines: List<String>): Redacted = Redacted.kestrel(lines.joinToString("\n"))

    private fun head(record: CommandRecord, zone: ZoneId, cap: Int): List<String> = listOfNotNull(
        RecordLines.head(record, zone),
        RecordLines.request(record, cap),
        RecordLines.answer(record, minOf(cap, ANSWER_CAP)),
        RecordLines.end(record),
        RecordLines.apps(record),
        record.checklist?.let(RecordLines::counts),
    )

    // The newest [most] of a record's [all] lines, offered newest first; the older ones and [dropped] more are counted.
    private fun newestFirst(all: List<String>, most: Int, dropped: Int, count: (Int) -> String): Part {
        val offered = all.takeLast(most)
        return Part(offered.asReversed().map(::listOf), count, notOffered = all.size - offered.size + dropped)
    }

    // The open items' count line, which reads the same whether some items come before it or none.
    private fun openLeftOut(count: Int): String =
        "    ($count open ${if (count == 1) "item is" else "items are"} left out.)"

    private fun olderLeftOut(count: Int): String =
        "($count earlier ${if (count == 1) "command of this conversation is" else "commands of this conversation are"} left out; " +
            "search_conversations finds them.)"

    /**
     * A part of the block that may leave something out: its [offered] items in the order they are taken, each item one
     * or more lines that are taken whole, and the line that [count]s what is left out, which also counts [notOffered].
     */
    private class Part(val offered: List<List<String>>, private val count: (Int) -> String, private val notOffered: Int = 0) {
        fun countLine(shown: Int): String? = (offered.size + notOffered - shown).takeIf { it > 0 }?.let(count)

        /** The line that this part needs when it shows nothing, the room that the block keeps for it. */
        val worst: String? get() = countLine(0)
    }

    /** What a part shows, in the order that it took them, and its count line, if it left something out. */
    private class Shown(private val taken: List<List<String>>, private val count: String?) {
        // The items in the order the part took them, then the count line.
        fun countLast(): List<String> = taken.flatten() + listOfNotNull(count)

        // The count line, then the items oldest first, for the parts that are taken newest first.
        fun countFirst(): List<String> = listOfNotNull(count) + taken.asReversed().flatten()
    }

    // The block's budget as it fills. Each line costs the tokens of itself and its line break, so the joined block never
    // costs more than the lines taken. [held] is the room kept for the count lines of the parts that are not filled yet.
    private class Fill(private val limit: Int) {
        private var used = 0
        private var held = 0

        private fun cost(lines: List<String>): Int = lines.sumOf { ScreenText.tokens(it + "\n") }

        /** Takes all of [lines] when they fit beside the kept room and [reserve], else nothing. */
        fun take(lines: List<String>, reserve: List<String> = emptyList()): Boolean {
            val cost = cost(lines)
            if (used + held + cost + cost(reserve) > limit) return false
            used += cost
            return true
        }

        /** Keeps room for each of [parts]' worst count line, all or none. */
        fun hold(parts: List<Part>): Boolean {
            val cost = cost(parts.mapNotNull { it.worst })
            if (used + held + cost > limit) return false
            held += cost
            return true
        }

        /**
         * Gives back the room kept for [part] and takes its items in order while each fits with room for the count line
         * of the rest. Its count line always fits: the room for it was either kept or reserved by the last item taken.
         */
        fun fill(part: Part): Shown {
            held -= cost(listOfNotNull(part.worst))
            val taken = mutableListOf<List<String>>()
            for (item in part.offered) {
                if (!take(item, reserve = listOfNotNull(part.countLine(taken.size + 1)))) break
                taken += item
            }
            val count = part.countLine(taken.size)
            val counted = count == null || take(listOf(count))
            check(counted) { "A part's count line did not fit in the room kept for it" }
            return Shown(taken, count)
        }
    }
}
