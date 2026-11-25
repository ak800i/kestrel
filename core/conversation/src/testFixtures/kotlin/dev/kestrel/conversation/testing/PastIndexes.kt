package dev.kestrel.conversation.testing

import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.store.IndexedRecord
import dev.kestrel.conversation.store.KeptRow
import dev.kestrel.conversation.store.PastIndex
import dev.kestrel.conversation.store.SearchIndex

/** The index of the closed conversations [snapshots], the oldest first, and the rows [kept], as the store builds it. */
fun PastIndex.Companion.of(snapshots: List<ConversationSnapshot>, kept: List<KeptRow>, shardPositions: Int = PastIndex.SHARD_POSITIONS): PastIndex {
    val builder = PastIndex.Builder(shardPositions)
    snapshots.forEach(builder::add)
    kept.forEach(builder::keep)
    return builder.build()
}

/** An index without conversations whose shards are full at [shardPositions]. */
fun PastIndex.Companion.empty(shardPositions: Int): PastIndex = PastIndex.Builder(shardPositions).build()

/** The folded words of [record], one of this index's records. */
fun SearchIndex.wordsOf(record: IndexedRecord): List<String> = record.words.map { words[it] }
