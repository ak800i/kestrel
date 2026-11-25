package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedConversationStoreTest {
    private val archive = MemoryArchive()
    private val reported = mutableListOf<String>()
    private val store = ArchivedConversationStore(archive) { reported += it }
    private val now = 100 * ArchivedConversationStore.DAY_MILLIS

    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private fun conversation(n: Int, last: Long, open: Boolean = false, request: String = "Request $n") = ConversationSnapshot(
        id = id(n),
        openedAtWall = last - 1_000,
        lastWall = last,
        open = open,
        testMode = true,
        records = listOf(CommandRecord(1, last - 1_000, InputChannel.TEXT, request)),
    )

    @Test
    fun aSnapshotRoundTripsAndOnlyItsHeaderIsPlain() = runTest {
        val snapshot = conversation(1, now, open = true)

        store.save(snapshot)

        assertEquals(ArchiveHeader(id(1).value, now - 1_000, now, open = true, commands = 1), archive.rows.getValue(id(1).value).header)
        assertTrue(archive.payload(id(1).value).contains("\"version\":1"))
        assertFalse(archive.payload(id(1).value).contains("\"open\""))
        assertEquals(snapshot, store.open())
        assertEquals(snapshot, ArchivedConversationStore(archive) {}.get(id(1)))
    }

    @Test
    fun aPayloadThatFailsItsTagIsDeletedAndReportedByItsKind() = runTest {
        store.save(conversation(1, now, open = true))
        archive.unreadable += id(1).value
        val fresh = ArchivedConversationStore(archive) { reported += it }

        assertNull(fresh.open())
        assertFalse(archive.rows.containsKey(id(1).value))
        assertEquals(listOf(ArchivedConversationStore.KIND), reported)
    }

    @Test
    fun aConversationIsOpenOnlyWhenItsRowSaysSo() = runTest {
        store.save(conversation(1, now, open = true))
        val row = archive.rows.getValue(id(1).value)
        // The table closed the row (plan 7C), while its payload is an older one that still says open.
        val payload = archive.payload(id(1).value).replaceFirst("{", "{\"open\":true,")
        archive.rows[id(1).value] = ArchiveRow(row.header.copy(open = false), payload.encodeToByteArray())
        val fresh = ArchivedConversationStore(archive) {}

        assertNull(fresh.open())
        assertEquals(listOf(id(1)), fresh.list().map { it.id })
        assertFalse(fresh.get(id(1))!!.open)
    }

    @Test
    fun aRowInAFormatThatThisVersionCannotReadIsLeftOutAndKept() = runTest {
        store.save(conversation(1, now, open = true))
        store.save(conversation(2, now - 1_000))
        archive.unknown += listOf(id(1).value, id(2).value)
        val fresh = ArchivedConversationStore(archive) { reported += it }

        assertNull(fresh.open())
        assertTrue(fresh.list().isEmpty())
        assertTrue(fresh.closedSince(0).isEmpty())
        assertEquals(setOf(id(1).value, id(2).value), archive.rows.keys)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun reopenClosesTheOpenOneAndOpensTheChosenOneInOneTransaction() = runTest {
        store.save(conversation(1, now - 5_000))
        store.save(conversation(2, now - 1_000, open = true))
        val closing = conversation(2, now - 500, open = true)

        val opened = store.reopen(id(1), closing, now, testMode = false)!!

        assertEquals(listOf(id(2).value, id(1).value), archive.transactions.last().sorted().reversed())
        assertEquals(opened, store.open())
        assertFalse(opened.testMode)
        assertEquals(now, opened.continuedAtWall)
        assertEquals(now, opened.lastWall)
        assertEquals(listOf(id(2)), store.list().map { it.id })
        assertEquals(now - 500, store.get(id(2))!!.lastWall)
    }

    @Test
    fun aConversationThatIsGoneCannotBeReopened() = runTest {
        store.save(conversation(2, now, open = true))

        assertNull(store.reopen(id(1), store.open(), now, testMode = true))
        assertEquals(id(2), store.open()!!.id)
    }

    @Test
    fun purgeKeepsTheOpenConversationAndDeletesOldAndFutureRows() = runTest {
        val day = ArchivedConversationStore.DAY_MILLIS
        store.save(conversation(1, now - 31 * day))
        store.save(conversation(2, now - 29 * day))
        store.save(conversation(3, now + 2 * day))
        store.save(conversation(4, now - 40 * day, open = true))

        assertEquals(2, store.purge(30, now))

        assertEquals(listOf(id(2)), store.list().map { it.id })
        assertEquals(id(4), store.open()!!.id)
    }

    @Test
    fun deleteAndDeleteAllRemoveRowsAndTheKey() = runTest {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now, open = true))

        store.delete(id(1))
        assertNull(store.get(id(1)))
        assertEquals(1, archive.rows.size)

        store.deleteAll()
        assertNull(store.open())
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.keyDeleted)
    }

    @Test
    fun eachRowIsReadOncePerProcess() = runTest {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000))
        val fresh = ArchivedConversationStore(archive) {}

        fresh.list()
        fresh.closedSince(0)
        fresh.get(id(1))
        fresh.open()

        assertEquals(2, archive.reads)
    }

    @Test
    fun theListShowsTheClosedConversationsNewestFirstWithTheirFirstRequest() = runTest {
        store.save(conversation(1, now - 2_000, request = "Find the restaurant"))
        store.save(conversation(2, now - 1_000, request = "Call Ana"))
        store.save(conversation(3, now, open = true))

        assertEquals(
            listOf(ConversationSummary(id(2), now - 2_000, now - 1_000, 1, "Call Ana"), ConversationSummary(id(1), now - 3_000, now - 2_000, 1, "Find the restaurant")),
            store.list(),
        )
        assertEquals(listOf(id(2)), store.closedSince(now - 1_500).map { it.id })
    }
}
