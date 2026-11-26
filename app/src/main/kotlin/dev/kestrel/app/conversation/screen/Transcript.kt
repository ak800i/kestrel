package dev.kestrel.app.conversation.screen

import dev.kestrel.agent.goal.ItemState
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind

/** The owner's kinds of line in a transcript. */
internal enum class OwnerLine { COMMAND, ANSWER, STEERING, TOO_LATE, CONTINUE }

/** One line of a transcript as the conversation screen draws it (design 10.1 and 9.5 of sub-project 7). toString prints no words. */
internal sealed interface TranscriptLine {
    /** The owner's words: a command with its time, an answer, steering or "continue", typed or spoken ([channel]). */
    data class Owner(val kind: OwnerLine, val text: String, val channel: InputChannel?, val atWall: Long? = null) : TranscriptLine {
        override fun toString(): String = "Owner(kind=$kind, text=${text.length} chars, channel=$channel)"
    }

    /** Kestrel's words, or its question when [asked]. */
    data class Kestrel(val text: String, val asked: Boolean) : TranscriptLine {
        override fun toString(): String = "Kestrel(text=${text.length} chars, asked=$asked)"
    }

    /** A command's final answer. */
    data class Answer(val text: String) : TranscriptLine {
        override fun toString(): String = "Answer(text=${text.length} chars)"
    }

    /** A goal's checklist, as one card that updates, and how many of its items are done. */
    data class Checklist(val items: List<ChecklistRow>, val done: Int) : TranscriptLine {
        override fun toString(): String = "Checklist(items=${items.size}, done=$done)"
    }

    /** A note in the record. */
    data class Note(val text: String) : TranscriptLine {
        override fun toString(): String = "Note(text=${text.length} chars)"
    }

    /** How many lines and data notes the record's bounds left out (design 8.1). */
    data class LeftOut(val lines: Int, val data: Int) : TranscriptLine

    /** The conversation was continued here (design 9.6). */
    data class Continued(val atWall: Long) : TranscriptLine

    /** The conversation ended here (design 6.2). */
    data class Ended(val atWall: Long) : TranscriptLine
}

/** One item of a goal's checklist on the screen. toString prints no words. */
internal data class ChecklistRow(val text: String, val state: ItemState) {
    override fun toString(): String = "ChecklistRow(text=${text.length} chars, state=$state)"
}

/**
 * Draws records as the conversation screen's lines (design 10.1 and 9.5 of sub-project 7): each command with its time and
 * the channel that it came on, what the record's bounds left out, Kestrel's words and questions, the owner's answers,
 * steering and "continue", the record's notes, the goal's checklist and the final answer; the line where the conversation
 * was continued, before the first command after it; and the line where it ended, last.
 */
internal object Transcript {
    fun of(records: List<CommandRecord>, continuedAtWall: Long? = null, endedAtWall: Long? = null): List<TranscriptLine> = buildList {
        var continued = continuedAtWall
        for (record in records) {
            continued?.takeIf { record.atWall >= it }?.let {
                add(TranscriptLine.Continued(it))
                continued = null
            }
            addAll(lines(record))
        }
        continued?.let { add(TranscriptLine.Continued(it)) }
        endedAtWall?.let { add(TranscriptLine.Ended(it)) }
    }

    private fun lines(record: CommandRecord): List<TranscriptLine> = buildList {
        add(TranscriptLine.Owner(OwnerLine.COMMAND, record.request, record.channel, record.atWall))
        if (record.droppedLines > 0 || record.droppedData > 0) add(TranscriptLine.LeftOut(record.droppedLines, record.droppedData))
        for (line in record.lines) {
            add(
                when (line.kind) {
                    LineKind.KESTREL_SAID -> TranscriptLine.Kestrel(line.text, asked = false)
                    LineKind.KESTREL_ASKED -> TranscriptLine.Kestrel(line.text, asked = true)
                    LineKind.OWNER_ANSWERED -> TranscriptLine.Owner(OwnerLine.ANSWER, line.text, line.channel)
                    LineKind.OWNER_STEERED -> TranscriptLine.Owner(OwnerLine.STEERING, line.text, line.channel)
                    // Plan 7B: words that came after the goal's final words, which Kestrel asked the owner to say again.
                    LineKind.OWNER_TOO_LATE -> TranscriptLine.Owner(OwnerLine.TOO_LATE, line.text, line.channel)
                    LineKind.OWNER_CONTINUED -> TranscriptLine.Owner(OwnerLine.CONTINUE, line.text, line.channel)
                    LineKind.NOTE -> TranscriptLine.Note(line.text)
                },
            )
        }
        record.checklist?.let { checklist -> add(TranscriptLine.Checklist(checklist.items.map { ChecklistRow(it.text, it.state) }, checklist.done)) }
        record.answer?.let { add(TranscriptLine.Answer(it)) }
    }
}
