package dev.kestrel.conversation.model

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.Checklist
import kotlinx.serialization.Serializable

/**
 * The structured summary of one command (design 8.1 of sub-project 7), which later commands get as context, which the
 * search reads and from which the conversation screen draws its transcript. Every text was redacted once when it entered,
 * and names each vault value by its kind's words. [answer] is the final sentence in its spoken form and [end] the goal's
 * end, both null while the goal works. [allowance] holds the wire names of the categories that the request and its
 * steering asked for (D16). [droppedLines] and [droppedData] count what the store's bounds left out. [condensed] is set
 * once, when a newer command made this one an older record of the block (design 8.3). toString prints only counts.
 */
@Serializable
data class CommandRecord(
    val number: Int,
    val atWall: Long,
    val channel: InputChannel,
    val request: String,
    val lines: List<RecordLine> = emptyList(),
    val answer: String? = null,
    val end: EndReason? = null,
    val data: List<DataNote> = emptyList(),
    val apps: List<AppUse> = emptyList(),
    val ledger: List<String> = emptyList(),
    val checklist: Checklist? = null,
    val allowance: Set<String> = emptySet(),
    val droppedLines: Int = 0,
    val droppedData: Int = 0,
    val condensed: Boolean = false,
) {
    override fun toString(): String =
        "CommandRecord(number=$number, channel=$channel, request=${request.length} chars, lines=${lines.size}, " +
            "answer=${answer?.length}, end=$end, data=${data.size}, apps=${apps.size}, ledger=${ledger.size}, " +
            "checklist=$checklist, allowance=${allowance.sorted()}, dropped=$droppedLines/$droppedData, condensed=$condensed)"
}

/** One line of a command's dialogue: what Kestrel said or asked, or what the owner answered, added or continued. */
@Serializable
data class RecordLine(val kind: LineKind, val text: String, val channel: InputChannel? = null) {
    override fun toString(): String = "RecordLine(kind=$kind, text=${text.length} chars, channel=$channel)"
}

/** A line's kind; the [owners] kinds are the owner's own words, which count as his in later commands (D8). */
enum class LineKind(val owners: Boolean) {
    KESTREL_SAID(false),
    KESTREL_ASKED(false),
    OWNER_ANSWERED(true),
    OWNER_STEERED(true),
    OWNER_CONTINUED(true),
    NOTE(false),
}

/**
 * A data tool's result as the model saw it: quoted and escaped, so it stays marked as data (design 8.2). [step] is the
 * goal's step, counted across its rounds as the bar counts it (design 10.5), or, when [routine], the step of the replayed
 * routine, which runs before the model's first step and numbers its own steps (plan 7A). [items] is how many items the
 * result held, which a condensed record names (design 8.3); the result's note below them is none.
 */
@Serializable
data class DataNote(val tool: String, val step: Int, val text: String, val items: Int, val routine: Boolean = false) {
    override fun toString(): String = "DataNote(tool=${tool.length} chars, step=$step, items=$items, routine=$routine, text=${text.length} chars)"
}

/** An app that an executed step acted in, by its launcher label. */
@Serializable
data class AppUse(val label: String, val packageName: String) {
    override fun toString(): String = "AppUse(label=${label.length} chars, package=${packageName.length} chars)"
}
