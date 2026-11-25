package dev.kestrel.conversation.store

import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The archive in memory keeps the port's contract, as plan 7C's archive on the encrypted tables does. */
class MemoryArchiveTest {
    // RecordArchive.read gives the rows in the order of the ids, so that a rebuild folds each page in the order of closedIds.
    @Test
    fun readGivesTheRowsInTheOrderOfTheIds() = runTest {
        val archive = MemoryArchive()
        val rows = listOf("a", "b", "c").mapIndexed { n, id -> ArchiveRow(ArchiveHeader(id, n.toLong(), n.toLong(), open = false, commands = 1), "{}".encodeToByteArray()) }
        archive.write(ArchiveChange(rows = rows))

        assertEquals(listOf("c", "a", "b"), archive.read(listOf("c", "gone", "a", "b")).map { it.header.id })
    }
}
