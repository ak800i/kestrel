package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot

/**
 * A closed conversation as the index of past conversations holds it (design 9.2 of sub-project 7): what the list shows
 * (design 9.5), with its first request cut at [PastIndex.FIRST_REQUEST] characters, and when it changed, so that the list,
 * retention and the search's reach need no body. Its records' words are in its shard's [IndexShard.search]. toString
 * prints only counts.
 */
data class PastConversation(val id: ConversationId, val openedAtWall: Long, val lastWall: Long, val commands: Int, val firstRequest: String?) {
    override fun toString(): String = "PastConversation(commands=$commands, firstRequest=${firstRequest?.length} chars)"
}

/**
 * A closed row that this version cannot read, because its format is newer (design 9.2): the index counts it, so that it
 * stays in step with the table, and retention purges it by its last change.
 */
data class KeptRow(val id: String, val lastWall: Long)

/**
 * What an index counts of the table's closed rows (design 9.2): how many there are, their newest last change and the sum of
 * their last changes, each 0 without rows. The archive gives the table's own stamp, and an index whose stamp differs is out
 * of step and is rebuilt.
 */
data class IndexStamp(val closed: Int, val newest: Long, val total: Long)

/**
 * The index of past conversations (design 9.2 of sub-project 7): for each closed conversation that this version can read,
 * what the list shows ([conversations], the newest last change first) and its records' words for the search ([searches]);
 * and the closed rows that it cannot read ([kept]). The open conversation is never in it. It is made of [shards], which
 * the archive keeps encrypted, each in a row of its own. A conversation that closes goes into the newest shard, and a new
 * shard starts when the newest holds [shardPositions] word positions or more, so that every change of the closed
 * conversations writes only the few shards that it changed ([changesFrom]), in the same transaction. The store keeps the
 * index in memory. An index never changes: [with], [keeping] and [without] give a new one, which shares the shards that did
 * not change, or this one when nothing changes. toString prints only counts.
 */
class PastIndex private constructor(shards: Collection<IndexShard>, val shardPositions: Int) {
    /** The shards, the newest first; none is empty. */
    val shards: List<IndexShard> = shards.filter { !it.isEmpty }.sortedByDescending { it.number }

    val conversations: List<PastConversation> by lazy { this.shards.flatMap { it.conversations }.sortedByDescending { it.lastWall } }

    /** The shards' words, one index per shard, as the search ranks them. */
    val searches: List<SearchIndex> get() = shards.map { it.search }

    val kept: List<KeptRow> by lazy { this.shards.flatMap { it.kept } }

    /** What this index counts of the closed rows, which must be the table's stamp. */
    val stamp: IndexStamp by lazy {
        val parts = this.shards.map { it.stamp }
        IndexStamp(parts.sumOf { it.closed }, parts.maxOfOrNull { it.newest } ?: 0, parts.sumOf { it.total })
    }

    // The number of the shard that holds each conversation and kept row, by its id.
    private val homes: Map<String, Int> by lazy {
        val homes = HashMap<String, Int>()
        for (shard in this.shards) {
            for (conversation in shard.conversations) homes[conversation.id.value] = shard.number
            for (row in shard.kept) homes[row.id] = shard.number
        }
        homes
    }

    private val known: Set<ConversationId> by lazy { conversations.mapTo(HashSet()) { it.id } }

    operator fun contains(id: ConversationId): Boolean = id in known

    /** This index with [snapshots], each a closed conversation that replaces its earlier entry, in the newest shard. */
    fun with(snapshots: List<ConversationSnapshot>): PastIndex {
        if (snapshots.isEmpty()) return this
        return into(snapshots.map { it.id.value }) { it.with(snapshots) }
    }

    /** This index with [rows] kept, each replacing its earlier entry, in the newest shard. */
    fun keeping(rows: List<KeptRow>): PastIndex {
        if (rows.isEmpty()) return this
        return into(rows.map { it.id }) { it.keeping(rows) }
    }

    /** This index without the conversations and kept rows [ids]. */
    fun without(ids: Set<String>): PastIndex {
        val numbers = ids.mapNotNullTo(HashSet()) { homes[it] }
        if (numbers.isEmpty()) return this
        return PastIndex(shards.map { if (it.number in numbers) it.without(ids) else it }, shardPositions)
    }

    /** The ids of the conversations and kept rows whose last change is before [oldest] or after [newest] (design 9.3). */
    fun outside(oldest: Long, newest: Long): Set<String> = shards.flatMapTo(HashSet()) { it.outside(oldest, newest) }

    /** What the archive must write to go from [before] to this index: the shards that are new or changed, and those that went. */
    fun changesFrom(before: PastIndex): ShardChanges {
        val earlier = before.shards.associateBy { it.number }
        val numbers = shards.mapTo(HashSet()) { it.number }
        return ShardChanges(shards.filter { earlier[it.number] !== it }, earlier.keys.filterTo(HashSet()) { it !in numbers })
    }

    override fun toString(): String = "PastIndex(shards=${shards.size}, conversations=${conversations.size}, kept=${kept.size})"

    // This index with the entries [ids] taken out of the shards that hold them and [added] to the newest shard, or to a new
    // one when the newest is full.
    private fun into(ids: List<String>, added: (IndexShard) -> IndexShard): PastIndex {
        val left = without(ids.toSet()).shards
        val newest = left.firstOrNull()?.takeIf { it.positions < shardPositions } ?: IndexShard.empty((left.firstOrNull()?.number ?: -1) + 1)
        return PastIndex(left.filter { it.number != newest.number } + added(newest), shardPositions)
    }

    /**
     * Builds an index one closed row at a time, as a rebuild reads them, the oldest last change first (design 9.2): each
     * conversation is folded into the newest shard's words as it is added, and a new shard starts when the newest is full,
     * so that a rebuild keeps no body after it read it, and the newest conversations are in the newest shard. toString
     * prints only counts.
     */
    class Builder(private val shardPositions: Int = SHARD_POSITIONS) {
        private inner class Shard(val number: Int) {
            val conversations = ArrayList<PastConversation>()
            val search = SearchIndex.Builder(words)
            val kept = ArrayList<KeptRow>()
            var positions = 0
        }

        private val words = HashMap<String, String>()
        private val shards = mutableListOf(Shard(0))

        fun add(snapshot: ConversationSnapshot) {
            val shard = newest()
            shard.conversations += IndexShard.listed(snapshot)
            shard.positions += shard.search.add(snapshot)
        }

        fun keep(row: KeptRow) {
            newest().kept += row
        }

        fun build(): PastIndex = PastIndex(shards.map { IndexShard.built(it.number, it.conversations, it.search.build(), it.kept) }, shardPositions)

        private fun newest(): Shard = shards.last().takeIf { it.positions < shardPositions } ?: Shard(shards.size).also { shards += it }

        override fun toString(): String = "PastIndex.Builder(shards=${shards.size})"
    }

    companion object {
        /** The length at which the index keeps a conversation's first request: the list's (design 9.5). */
        const val FIRST_REQUEST = 80

        /** The word positions at which the newest shard is full: about 200 KB encoded, a close's whole work (design R3). */
        const val SHARD_POSITIONS = 100_000

        val EMPTY = PastIndex(emptyList(), SHARD_POSITIONS)

        /** An index without conversations whose shards are full at [shardPositions]. */
        fun empty(shardPositions: Int): PastIndex = PastIndex(emptyList(), shardPositions)

        /** The index of the closed conversations [snapshots], the oldest first, and the rows [kept]. */
        fun of(snapshots: List<ConversationSnapshot>, kept: List<KeptRow>, shardPositions: Int = SHARD_POSITIONS): PastIndex {
            val builder = Builder(shardPositions)
            snapshots.forEach(builder::add)
            kept.forEach(builder::keep)
            return builder.build()
        }

        /**
         * An index read back from the archive, shard by shard: each number once, and each conversation and kept row in one
         * shard only; otherwise it throws [IllegalArgumentException].
         */
        fun restored(shards: List<IndexShard>, shardPositions: Int = SHARD_POSITIONS): PastIndex {
            require(shards.map { it.number }.toSet().size == shards.size) { "A shard is in the index twice" }
            val ids = HashSet<String>()
            for (shard in shards) {
                for (id in shard.conversations.map { it.id.value } + shard.kept.map { it.id }) require(ids.add(id)) { "A conversation is in two shards" }
            }
            return PastIndex(shards, shardPositions)
        }
    }
}

/** What a change writes of the index: the shards to store, each replacing its row, and the numbers of those whose rows go. */
class ShardChanges(val put: List<IndexShard>, val gone: Set<Int>) {
    val isEmpty: Boolean get() = put.isEmpty() && gone.isEmpty()

    override fun toString(): String = "ShardChanges(put=${put.size}, gone=${gone.size})"
}
