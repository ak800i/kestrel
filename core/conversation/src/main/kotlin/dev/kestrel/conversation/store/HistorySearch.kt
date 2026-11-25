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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The past conversation that "continue the conversation about X" found (design 9.6 of sub-project 7). */
data class ConversationMatch(val id: ConversationId, val openedAtWall: Long, val firstRequest: String) {
    override fun toString(): String = "ConversationMatch(firstRequest=${firstRequest.length} chars)"
}

/**
 * The on-device search over past conversations (design 9.4 of sub-project 7), behind plan 7A's `search_conversations`, and
 * the search for a conversation to continue (design 9.6). It has three parts: the entries' source, which folds the records
 * of the closed conversations within retention and of the open one into [SearchEntry]s (never an observation or a
 * screenshot, which no record holds); [HistoryRanking], which matches and orders them; and the details of the best
 * matches, which their records give. The source keeps each record's entry in memory only, like the vault, until the record
 * changes or goes, or [forgetAll] drops them.
 */
class HistorySearch(
    private val store: ConversationStore,
    private val retentionDays: () -> Int,
    private val clock: ConversationClock,
) {
    private class Indexed(val record: CommandRecord, val entry: SearchEntry)

    private val mutex = Mutex()
    private val indexes = HashMap<RecordRef, Indexed>()

    /** How many records' entries are kept now. */
    val indexed: Int get() = synchronized(indexes) { indexes.size }

    /** The commands that match [query], other than [excluding], which asks. */
    suspend fun search(query: HistoryQuery, excluding: RecordRef? = null): HistoryResult = mutex.withLock {
        if (HistoryRanking.wanted(query.text).isEmpty()) return@withLock HistoryResult(emptyList())
        val reach = store.closedSince(since()) + listOfNotNull(store.open())
        val entries = entries(reach, prune = true).filter { it.ref != excluding }
        val ranked = HistoryRanking.rank(entries, query, clock.zone())
        HistoryResult(details(ranked.take(HistoryPort.MAX_MATCHES)), more = ranked.size > HistoryPort.MAX_MATCHES)
    }

    /** The search as the agent's port for the command [excluding], which asks. */
    fun port(excluding: RecordRef?): HistoryPort = object : HistoryPort {
        override suspend fun search(query: HistoryQuery): HistoryResult = this@HistorySearch.search(query, excluding)

        override fun toString(): String = "HistorySearch.port"
    }

    /**
     * The closed conversation within retention that holds the best match for [topic], the newer on a tie; without a topic
     * (or with only words that hold none), the newest closed conversation. Null when none matches.
     */
    suspend fun conversation(topic: String?): ConversationMatch? = mutex.withLock {
        // closedSince gives the newest change first.
        val closed = store.closedSince(since()).filter { it.records.isNotEmpty() }
        if (topic == null || HistoryRanking.wanted(topic).isEmpty()) return@withLock closed.firstOrNull()?.let(::matchOf)
        val best = HistoryRanking.rank(entries(closed, prune = false), HistoryQuery(topic), clock.zone()).firstOrNull()
        best?.let { ranked -> closed.firstOrNull { it.id == ranked.entry.ref.conversation } }?.let(::matchOf)
    }

    /** Drops every kept entry ("delete history", design 9.7). */
    fun forgetAll() = synchronized(indexes) { indexes.clear() }

    /**
     * The entries of [conversations]' records, each folded once while its record stays the same. When [conversations] are
     * all that the search reaches ([prune]), the entries of the records that went (deleted, purged or out of reach) go too.
     */
    private fun entries(conversations: List<ConversationSnapshot>, prune: Boolean): List<SearchEntry> = synchronized(indexes) {
        val entries = conversations.flatMap { snapshot ->
            snapshot.records.map { record ->
                val ref = RecordRef(snapshot.id, record.number)
                indexes[ref]?.takeIf { it.record === record }?.entry
                    ?: SearchEntry.of(snapshot.id, record).also { indexes[ref] = Indexed(record, it) }
            }
        }
        if (prune) {
            val kept = entries.mapTo(HashSet()) { it.ref }
            indexes.keys.retainAll(kept)
        }
        entries
    }

    /** The details of [ranked], in its order, from their records. */
    private suspend fun details(ranked: List<RankedEntry>): List<HistoryMatch> {
        val conversations = HashMap<ConversationId, ConversationSnapshot?>()
        return ranked.mapNotNull { (entry) ->
            val snapshot = conversations.getOrPut(entry.ref.conversation) { store.get(entry.ref.conversation) }
            snapshot?.records?.firstOrNull { it.number == entry.ref.record }?.let(::match)
        }
    }

    private fun since(): Long = clock.wallMillis() - retentionDays() * ArchivedConversationStore.DAY_MILLIS

    private fun matchOf(snapshot: ConversationSnapshot) = ConversationMatch(snapshot.id, snapshot.openedAtWall, snapshot.records.first().request)

    private fun match(record: CommandRecord) = HistoryMatch(
        atMillis = record.atWall,
        command = record.number,
        request = record.request,
        answer = record.answer,
        apps = record.apps.map { it.label },
        data = record.data.map { HistoryData(it.tool, it.text) },
    )

    override fun toString(): String = "HistorySearch(indexed=$indexed)"
}
