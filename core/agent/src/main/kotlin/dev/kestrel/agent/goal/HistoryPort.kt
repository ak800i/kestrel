package dev.kestrel.agent.goal

import java.time.LocalDate

/**
 * The on-device search over past conversations, behind the tool `search_conversations` (design 5.4 and 9.4 of
 * sub-project 7): `:core:conversation`'s `HistorySearch` implements it for each goal's round, and `GoalRunner`'s "save that
 * as" round passes [NoHistory]. Its [HistoryResult] holds the best matches first, the order that the tool's description
 * promises the model.
 */
interface HistoryPort {
    suspend fun search(query: HistoryQuery): HistoryResult

    companion object {
        /** A result holds at most this many earlier commands (design 9.4). */
        const val MAX_MATCHES = 5
    }
}

/** The words to look for, and the first and last day to search, both included; null for no bound. */
data class HistoryQuery(val text: String, val from: LocalDate? = null, val to: LocalDate? = null) {
    override fun toString(): String = "HistoryQuery(text=${text.length} chars, from=$from, to=$to)"
}

/**
 * The earlier commands that match, at most [HistoryPort.MAX_MATCHES], best match first (design 9.4 of sub-project 7): a
 * record scores by how many of the query's words it holds, and of two that score alike the newer comes first. [more] says
 * that more commands matched than [matches] holds. [unavailable] says why the search could not run (design 9.2): Kestrel is
 * rebuilding the index of its past conversations, or cannot read it now; such a result has no match ([REBUILDING],
 * [FAILED]).
 */
data class HistoryResult(val matches: List<HistoryMatch>, val more: Boolean = false, val unavailable: HistoryUnavailable? = null) {
    init {
        require(unavailable == null || (matches.isEmpty() && !more)) { "A search that could not run has no match" }
    }

    override fun toString(): String = "HistoryResult(matches=${matches.size}, more=$more, unavailable=$unavailable)"

    companion object {
        val REBUILDING = HistoryResult(emptyList(), unavailable = HistoryUnavailable.REBUILDING)
        val FAILED = HistoryResult(emptyList(), unavailable = HistoryUnavailable.FAILED)
    }
}

/** Why a search over past conversations could not run (design 9.2 of sub-project 7). */
enum class HistoryUnavailable {
    /** The index of past conversations is being rebuilt from them. */
    REBUILDING,

    /** The index could not be read or rebuilt; Kestrel has noted it once and tries again by itself later. */
    FAILED,
}

/**
 * One earlier command that matched (design 9.4), as its record stored it: when it was given ([atMillis], wall clock), its
 * number in its conversation, the owner's [request], Kestrel's final [answer], the [apps] that it used (acted in or
 * opened) by their labels, and its data notes, each the result that the model saw then.
 */
data class HistoryMatch(
    val atMillis: Long,
    val command: Int,
    val request: String,
    val answer: String?,
    val apps: List<String>,
    val data: List<HistoryData>,
) {
    override fun toString(): String =
        "HistoryMatch(command=$command, request=${request.length} chars, answer=${answer?.length}, apps=${apps.size}, data=${data.size})"
}

/**
 * One data note of a matched command: its tool and its result's text, quoted and escaped as the model saw it then, kept by
 * whole lines within the store's bound, the rest counted on a last line of Kestrel's own.
 */
data class HistoryData(val tool: String, val text: String) {
    override fun toString(): String = "HistoryData(tool=$tool, text=${text.length} chars)"
}

/** The history of a round that searches no conversations, as "save that as" (plan 7D): nothing matches. */
object NoHistory : HistoryPort {
    override suspend fun search(query: HistoryQuery): HistoryResult = HistoryResult(emptyList())
}
