package dev.kestrel.conversation.store

import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.conversation.model.ConversationSnapshot

/**
 * A part of the index of past conversations (design 9.2 of sub-project 7): some closed conversations and kept rows, with
 * what the list shows of each conversation ([conversations], the newest last change first), its records' words ([search])
 * and the kept rows ([kept]). The archive keeps each shard encrypted in a row of its own, named by its [number], so that a
 * close re-encodes and re-encrypts only the newest shard, which holds at most about [PastIndex.SHARD_POSITIONS] word
 * positions and [PastIndex.SHARD_BYTES] bytes before Deflate, and one conversation more, however long the retention and
 * however heavy the use. A shard never changes: [with], [keeping] and [without]
 * give a new one, or this one when nothing changes. toString prints only counts.
 */
class IndexShard private constructor(val number: Int, val conversations: List<PastConversation>, val search: SearchIndex, val kept: List<KeptRow>) {
    /** What this shard counts of the closed rows: its part of the index's stamp. */
    val stamp: IndexStamp by lazy {
        val last = conversations.map { it.lastWall } + kept.map { it.lastWall }
        IndexStamp(last.size, last.maxOrNull() ?: 0, last.sum())
    }

    /** How many word positions the shard's records hold, which its size follows. */
    val positions: Int by lazy { search.records.sumOf { it.words.size } }

    /** The most bytes that the shard takes in `PastIndexCodec`'s format before Deflate; a shard is full by it too. */
    val plainBytes: Long by lazy {
        PastIndexCodec.SHARD_HEADER_BYTES + search.plainBytes + conversations.sumOf(PastIndexCodec::conversationBytes) + kept.sumOf(PastIndexCodec::keptBytes)
    }

    /** Whether the shard holds nothing, so that the archive keeps no row for it. */
    val isEmpty: Boolean get() = conversations.isEmpty() && kept.isEmpty()

    /** This shard with [snapshots], closed conversations, each replacing its earlier entry. */
    fun with(snapshots: List<ConversationSnapshot>): IndexShard {
        if (snapshots.isEmpty()) return this
        val ids = snapshots.mapTo(HashSet()) { it.id }
        val values = ids.mapTo(HashSet()) { it.value }
        val listed = (conversations.filter { it.id !in ids } + snapshots.map(::listed)).sortedByDescending { it.lastWall }
        return IndexShard(number, listed, search.without(ids) + SearchIndex.of(snapshots), kept.filter { it.id !in values })
    }

    /** This shard with [rows] kept, each replacing its earlier entry. */
    fun keeping(rows: List<KeptRow>): IndexShard {
        if (rows.isEmpty()) return this
        val ids = rows.mapTo(HashSet()) { it.id }
        val gone = conversations.filter { it.id.value in ids }.mapTo(HashSet()) { it.id }
        return IndexShard(number, conversations.filter { it.id !in gone }, search.without(gone), kept.filter { it.id !in ids } + rows)
    }

    /** This shard without the conversations and kept rows [ids]. */
    fun without(ids: Set<String>): IndexShard {
        val gone = conversations.filter { it.id.value in ids }.mapTo(HashSet()) { it.id }
        if (gone.isEmpty() && kept.none { it.id in ids }) return this
        return IndexShard(number, conversations.filter { it.id !in gone }, search.without(gone), kept.filter { it.id !in ids })
    }

    /** The ids of the conversations and kept rows whose last change is before [oldest] or after [newest] (design 9.3). */
    fun outside(oldest: Long, newest: Long): Set<String> {
        fun out(last: Long) = last < oldest || last > newest
        return conversations.filter { out(it.lastWall) }.mapTo(HashSet()) { it.id.value } + kept.filter { out(it.lastWall) }.map { it.id }
    }

    override fun toString(): String = "IndexShard(number=$number, conversations=${conversations.size}, kept=${kept.size}, $search)"

    companion object {
        fun empty(number: Int): IndexShard = IndexShard(number, emptyList(), SearchIndex.EMPTY, emptyList())

        /**
         * A shard read back from the archive (`PastIndexCodec`): its conversations must come the newest last change first,
         * each id once, with no more records in [search] than each counts commands, each record once, and no record of
         * another conversation; otherwise it throws [IllegalArgumentException].
         */
        fun restored(number: Int, conversations: List<PastConversation>, search: SearchIndex, kept: List<KeptRow>): IndexShard {
            require(number >= 0) { "A shard's number is negative" }
            require((1 until conversations.size).all { conversations[it - 1].lastWall >= conversations[it].lastWall }) { "The conversations are not in order" }
            val ids = conversations.map { it.id.value } + kept.map { it.id }
            require(ids.toSet().size == ids.size) { "A conversation is in the shard twice" }
            require(search.records.map { it.ref }.toSet().size == search.records.size) { "A record is in the shard twice" }
            val records = search.records.groupingBy { it.ref.conversation }.eachCount()
            val known = conversations.mapTo(HashSet()) { it.id }
            require(records.keys.all { it in known }) { "A record belongs to no conversation of the shard" }
            require(conversations.all { (records[it.id] ?: 0) <= it.commands }) { "A conversation has more records than commands" }
            return IndexShard(number, conversations, search, kept)
        }

        /** A shard that a [PastIndex.Builder] made: its conversations, in any order, and its records in [search]. */
        internal fun built(number: Int, conversations: List<PastConversation>, search: SearchIndex, kept: List<KeptRow>): IndexShard =
            IndexShard(number, conversations.sortedByDescending { it.lastWall }, search, kept)

        /** What the list shows of [snapshot], its first request cut at [PastIndex.FIRST_REQUEST] characters. */
        internal fun listed(snapshot: ConversationSnapshot): PastConversation = PastConversation(
            snapshot.id, snapshot.openedAtWall, snapshot.lastWall, snapshot.records.size,
            snapshot.records.firstOrNull()?.request?.let { ScreenText.cut(it, PastIndex.FIRST_REQUEST) },
        )
    }
}
