package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PastIndexTest {
    private val day = ArchivedConversationStore.DAY_MILLIS

    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private fun closed(n: Int, last: Long, vararg requests: String) = ConversationSnapshot(
        id(n), last - 1_000, last, open = false, testMode = true,
        records = requests.mapIndexed { index, request -> CommandRecord(index + 1, last - 1_000 + index, InputChannel.TEXT, request) },
    )

    @Test
    fun aClosedConversationEntersWithWhatTheListShowsAndLeavesWithItsWords() {
        val long = "Plan the trip to Novi Sad and book a hotel near the river for the long weekend in December, with parking"
        val index = PastIndex.EMPTY.with(listOf(closed(1, 100, long, "Book it"), closed(2, 200, "Call Ana")))

        assertEquals(listOf(id(2), id(1)), index.conversations.map { it.id })
        assertEquals(PastConversation(id(1), -900, 100, 2, long.take(PastIndex.FIRST_REQUEST).trimEnd() + "…"), index.conversations[1])
        assertEquals(3, index.searches.single().records.size)
        assertTrue(id(1) in index)

        val left = index.without(setOf(id(1).value))
        assertEquals(listOf(id(2)), left.conversations.map { it.id })
        assertEquals(listOf("ana", "call"), left.searches.single().words)
        assertSame(left, left.without(setOf(id(1).value)))
        assertSame(left, left.with(emptyList()))
    }

    @Test
    fun theStampCountsEveryClosedRowAndRetentionFindsTheOldOnesAndTheKeptOnes() {
        val index = PastIndex.of(listOf(closed(1, 100, "a"), closed(2, 300, "b")), listOf(KeptRow("later", 50)))

        assertEquals(IndexStamp(closed = 3, newest = 300, total = 450), index.stamp)
        assertEquals(IndexStamp(0, 0, 0), PastIndex.EMPTY.stamp)
        assertEquals(setOf(id(1).value, "later"), index.outside(oldest = 101, newest = 300))
        assertEquals(IndexStamp(1, 300, 300), index.without(setOf(id(1).value, "later")).stamp)
    }

    @Test
    fun anIndexComesBackFromItsBytes() {
        val record = CommandRecord(
            1, 5, InputChannel.TEXT, "Пронађи ресторан", answer = "Ambar 🙂",
            data = listOf(DataNote("list_sms", 1, "body: \"Ambar\"", items = 1)),
        )
        val index = PastIndex.of(
            listOf(ConversationSnapshot(id(1), 1, 10, testMode = true, records = listOf(record)), closed(2, 20), closed(3, 30, "Call Ana")),
            listOf(KeptRow("later", 7)),
        )

        val shard = index.shards.single()

        val read = PastIndexCodec.decode(shard.number, PastIndexCodec.encode(shard))

        assertEquals(shard.number, read.number)
        assertEquals(shard.conversations, read.conversations)
        assertEquals(shard.kept, read.kept)
        assertEquals(shard.search.words, read.search.words)
        assertEquals(shard.search.records.associate { it.ref to shard.search.wordsOf(it) }, read.search.records.associate { it.ref to read.search.wordsOf(it) })
        assertEquals(shard.stamp, read.stamp)
        assertEquals(PastIndexCodec.FORMAT, PastIndexCodec.encode(shard)[0])
    }

    @Test
    fun bytesInAnotherFormatOrThatDoNotDecodeAreRefused() {
        val shard = PastIndex.of(listOf(closed(1, 10, "Call Ana")), emptyList()).shards.single()
        val bytes = PastIndexCodec.encode(shard)

        for (wrong in listOf(byteArrayOf(), byteArrayOf(2) + bytes.copyOfRange(1, bytes.size), bytes.copyOf(bytes.size - 3), bytes + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) { PastIndexCodec.decode(shard.number, wrong) }
        }
    }

    // Design 9.2: a close re-encodes only the newest shard, which is never much larger than its limit, and a conversation
    // that changes again moves to the newest shard.
    @Test
    fun aCloseGoesToTheNewestShardUntilItIsFullAndAConversationThatChangesAgainMoves() {
        val index = PastIndex.empty(shardPositions = 4).with(listOf(closed(1, 100, "Call Ana"))).with(listOf(closed(2, 200, "Book the hotel")))
        val full = index.with(listOf(closed(3, 300, "Plan the trip")))

        assertEquals(listOf(listOf(id(3)), listOf(id(2), id(1))), full.shards.map { shard -> shard.conversations.map { it.id } })
        assertEquals(listOf(1), full.changesFrom(index).put.map { it.number })
        assertSame(index.shards.single(), full.shards.last())

        val moved = full.with(listOf(closed(1, 400, "Call Ana again")))

        assertEquals(listOf(id(1), id(3), id(2)), moved.conversations.map { it.id })
        assertEquals(listOf(listOf(id(1), id(3)), listOf(id(2))), moved.shards.map { shard -> shard.conversations.map { it.id } })
        assertEquals(setOf(0, 1), moved.changesFrom(full).put.mapTo(HashSet()) { it.number })
        assertEquals(setOf(0), moved.without(setOf(id(2).value)).changesFrom(moved).gone)
        assertEquals(IndexStamp(3, 400, 400 + 300 + 200), moved.stamp)
    }

    // A rebuild folds the conversations, the oldest first, into a builder; the changes fold them into the index one at a
    // time. Both must make the same shards.
    @Test
    fun aRebuildAndTheChangesOneAtATimeMakeTheSameIndex() {
        val random = Random(9)
        val words = listOf("ambar", "mila", "restoran", "hotel", "novi", "sad", "viber", "poruka", "Đorđe", "Ђорђе")
        val snapshots = (1..40).map { n -> closed(n, random.nextLong(0, 200 * day), *Array(random.nextInt(1, 4)) { List(3) { words.random(random) }.joinToString(" ") }) }
            .sortedBy { it.lastWall }
        val kept = listOf(KeptRow("later", 5 * day), KeptRow("newer", 150 * day))

        val built = PastIndex.of(snapshots, kept, shardPositions = 20)
        val folded = snapshots.fold(PastIndex.empty(shardPositions = 20)) { index, snapshot -> index.with(listOf(snapshot)) }.keeping(kept)

        assertTrue(built.shards.size > 3)
        assertEquals(folded.conversations, built.conversations)
        assertEquals(folded.kept.toSet(), built.kept.toSet())
        assertEquals(folded.shards.map { it.number }, built.shards.map { it.number })
        for ((one, other) in folded.shards.zip(built.shards)) {
            assertEquals(one.conversations, other.conversations)
            assertEquals(one.search.words, other.search.words)
            assertEquals(one.search.records.associate { it.ref to one.search.wordsOf(it) }, other.search.records.associate { it.ref to other.search.wordsOf(it) })
        }
        assertEquals(folded.stamp, built.stamp)
    }

    @Test
    fun anIndexWhoseShardsDisagreeIsRefused() {
        val first = PastIndex.of(listOf(closed(1, 10, "Call Ana")), emptyList()).shards.single()
        val again = PastIndex.of(listOf(closed(2, 20, "Book it"), closed(1, 30, "Call Ana")), emptyList(), shardPositions = 1).shards.first()

        assertEquals(1, again.number)
        assertThrows(IllegalArgumentException::class.java) { PastIndex.restored(listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) { PastIndex.restored(listOf(first, again)) }
        assertEquals(listOf(id(1)), PastIndex.restored(listOf(first)).conversations.map { it.id })
    }

    // Design 9.2 and R3 of sub-project 7: 30 days of heavy use make two shards, and a close re-encodes at most one of them.
    @Test
    fun theIndexOf30HeavyDaysStaysSmall() {
        val random = Random(30)
        val pool = List(25_000) { n -> "w${n.toString(36)}" + List(random.nextInt(2, 8)) { 'a' + random.nextInt(26) }.joinToString("") }
        val conversations = List(300) { n ->
            val records = List(2) { r -> CommandRecord(r + 1, n * 10L + r, InputChannel.TEXT, List(300) { pool.random(random) }.joinToString(" ")) }
            ConversationSnapshot(id(n + 1), n * 10L, n * 10L + 1, testMode = true, records = records)
        }

        val shards = PastIndex.of(conversations, emptyList()).shards
        val bytes = shards.map { PastIndexCodec.encode(it) }

        assertEquals(2, shards.size)
        assertTrue("${bytes.map { it.size }} bytes", bytes.sumOf { it.size } < 550_000 && bytes.all { it.size < 300_000 })
        assertEquals(300, shards.zip(bytes).sumOf { (shard, encoded) -> PastIndexCodec.decode(shard.number, encoded).conversations.size })
        assertFalse(PastIndex.EMPTY.toString().contains("w0"))
    }
}
