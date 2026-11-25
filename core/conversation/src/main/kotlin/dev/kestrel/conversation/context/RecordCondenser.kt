package dev.kestrel.conversation.context

import dev.kestrel.conversation.model.CommandRecord
import java.time.ZoneId

/**
 * The older records of a conversation, condensed by code (design 8.3 of sub-project 7). At the start of each new command
 * every earlier record but the newest is marked [CommandRecord.condensed], once, and stays so, also after the conversation
 * is continued. A condensed record keeps all its parts for the search and the transcript; in the records block it shows
 * only its request, its answer, its end, its apps, its checklist's progress and one line per data note, which depend on
 * its own fields and the zone of its time only, so its text in the block never changes within a zone.
 */
object RecordCondenser {
    /** The request's and the answer's length in a condensed record. */
    const val CAP = 200

    fun condense(earlier: List<CommandRecord>): List<CommandRecord> =
        earlier.mapIndexed { index, record -> if (index < earlier.lastIndex && !record.condensed) record.copy(condensed = true) else record }

    fun lines(record: CommandRecord, zone: ZoneId): List<String> = listOfNotNull(
        RecordLines.head(record, zone),
        RecordLines.request(record, CAP),
        RecordLines.answer(record, CAP),
        RecordLines.end(record),
        RecordLines.apps(record),
        record.checklist?.let { RecordLines.progress(it, CAP) },
    ) + record.data.map(RecordLines::gave)
}
