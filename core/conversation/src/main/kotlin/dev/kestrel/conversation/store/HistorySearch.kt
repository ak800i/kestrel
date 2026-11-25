package dev.kestrel.conversation.store

import dev.kestrel.agent.goal.HistoryData
import dev.kestrel.agent.goal.HistoryMatch
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.conversation.engine.ConversationClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.RecordRef

/** The past conversation that "continue the conversation about X" found (design 9.6 of sub-project 7). */
data class ConversationMatch(val id: ConversationId, val openedAtWall: Long, val firstRequest: String) {
    override fun toString(): String = "ConversationMatch(firstRequest=${firstRequest.length} chars)"
}

/**
 * What the search for a conversation to continue gave (design 9.6): a match, none, or the mark that the index of past
 * conversations is being rebuilt, or cannot be read now.
 */
sealed interface ConversationLookup {
    data class Found(val match: ConversationMatch) : ConversationLookup

    data object NotFound : ConversationLookup

    data object Rebuilding : ConversationLookup

    data object Unavailable : ConversationLookup
}

/**
 * The on-device search over past conversations (design 9.4 of sub-project 7), behind plan 7A's `search_conversations`, and
 * the search for a conversation to continue (design 9.6). It reads what the store's [ConversationStore.searchable] gives:
 * the index of past conversations, whose records within retention it searches, and the open conversation, whose records
 * other than the asking command's it folds whole at each search (never an observation or a screenshot, which no record holds).
 * [HistoryRanking] matches and orders them, and only the best matches' bodies are read, in one call, for their details.
 * While the index is being rebuilt, or cannot be read, the search says so ([HistoryResult.REBUILDING],
 * [HistoryResult.FAILED], [ConversationLookup.Rebuilding], [ConversationLookup.Unavailable]) and gives no match, not even
 * from the open conversation, because a part of the matches would read as "nothing older matched" (design 9.4). It keeps
 * nothing itself, and a search that "delete history" overtook, which the store's [ConversationStore.generation] tells,
 * gives out nothing that it read (design 9.7). Each search ranks the whole index, so a caller that searches as the owner
 * types (plan 7F) waits until the typing pauses.
 */
class HistorySearch(
    private val store: ConversationStore,
    private val retentionDays: () -> Int,
    private val clock: ConversationClock,
) {
    /** The commands that match [query], other than [excluding], which asks. */
    suspend fun search(query: HistoryQuery, excluding: RecordRef? = null): HistoryResult {
        val generation = store.generation
        val searchable = when (val read = store.searchable()) {
            is Searchable.Ready -> read
            Searchable.Rebuilding -> return HistoryResult.REBUILDING
            Searchable.Failed -> return HistoryResult.FAILED
        }
        val open = searchable.open
        val reach = reach(searchable.past).mapTo(HashSet()) { it.id }
        val indexes = searchable.past.searches + listOfNotNull(open?.let(SearchIndex::whole))
        val ranked = HistoryRanking.rank(indexes, query, clock.zone(), reached = { record ->
            record.ref != excluding && (record.ref.conversation in reach || record.ref.conversation == open?.id)
        })
        val matches = details(ranked.take(HistoryPort.MAX_MATCHES), open)
        if (store.generation != generation) return HistoryResult(emptyList())
        return HistoryResult(matches, more = ranked.size > HistoryPort.MAX_MATCHES)
    }

    /** The search as the agent's port for the command [excluding], which asks. */
    fun port(excluding: RecordRef?): HistoryPort = object : HistoryPort {
        override suspend fun search(query: HistoryQuery): HistoryResult = this@HistorySearch.search(query, excluding)

        override fun toString(): String = "HistorySearch.port"
    }

    /**
     * The closed conversation within retention that holds the best match for [topic], the newer on a tie; without a topic
     * (or with only words that hold none), the newest closed conversation that holds a command.
     */
    suspend fun conversation(topic: String?): ConversationLookup {
        val generation = store.generation
        val searchable = when (val read = store.searchable()) {
            is Searchable.Ready -> read
            Searchable.Rebuilding -> return ConversationLookup.Rebuilding
            Searchable.Failed -> return ConversationLookup.Unavailable
        }
        val closed = reach(searchable.past).filter { it.firstRequest != null }
        val best = if (topic == null || HistoryRanking.wanted(topic).isEmpty()) {
            closed.firstOrNull()
        } else {
            val byId = closed.associateBy { it.id }
            HistoryRanking.rank(searchable.past.searches, HistoryQuery(topic), clock.zone(), reached = { it.ref.conversation in byId })
                .firstOrNull()?.let { byId[it.entry.ref.conversation] }
        }
        val request = best?.firstRequest?.takeIf { store.generation == generation } ?: return ConversationLookup.NotFound
        return ConversationLookup.Found(ConversationMatch(best.id, best.openedAtWall, request))
    }

    // The closed conversations within retention, the newest change first.
    private fun reach(past: PastIndex): List<PastConversation> {
        val since = clock.wallMillis() - retentionDays() * ArchivedConversationStore.DAY_MILLIS
        return past.conversations.filter { it.lastWall >= since }
    }

    /** The details of [ranked], in its order, from their records: the open conversation's from memory, the others' bodies read in one call. */
    private suspend fun details(ranked: List<RankedEntry>, open: ConversationSnapshot?): List<HistoryMatch> {
        val closed = ranked.mapTo(LinkedHashSet()) { it.entry.ref.conversation } - setOfNotNull(open?.id)
        val bodies = store.getAll(closed) + listOfNotNull(open).associateBy { it.id }
        return ranked.mapNotNull { (entry) ->
            bodies[entry.ref.conversation]?.records?.firstOrNull { it.number == entry.ref.record }?.let(::match)
        }
    }

    private fun match(record: CommandRecord) = HistoryMatch(
        atMillis = record.atWall,
        command = record.number,
        request = record.request,
        answer = record.answer,
        apps = record.apps.map { it.label },
        data = record.data.map { HistoryData(it.tool, it.text) },
    )

    override fun toString(): String = "HistorySearch"
}
