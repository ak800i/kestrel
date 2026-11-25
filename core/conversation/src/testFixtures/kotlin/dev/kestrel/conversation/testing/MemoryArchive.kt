package dev.kestrel.conversation.testing

import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.RecordArchive

/**
 * The archive in memory, for the JVM tests: the rows by id, the ids whose payload fails its tag ([unreadable]) or is in a
 * format that this version cannot read ([unknown]), each write's ids in order ([transactions]), the port's calls by name
 * in order ([calls]), and whether the key was deleted. Its writes keep at most one row open, as plan 7C's table does. A
 * change named in [interruptions] (`write`, `delete`, `deleteAll`, `deleteOutside`) is made and then throws its
 * exception, once, as a Room call does when its coroutine is cancelled while the call runs.
 */
class MemoryArchive : RecordArchive {
    val rows = LinkedHashMap<String, ArchiveRow>()
    val unreadable = mutableSetOf<String>()
    val unknown = mutableSetOf<String>()
    val transactions = mutableListOf<List<String>>()
    val calls = mutableListOf<String>()
    val interruptions = mutableMapOf<String, Throwable>()
    var keyDeleted = false

    override suspend fun readAll(): List<ArchiveEntry> {
        calls += "readAll"
        return rows.values.sortedByDescending { it.header.lastMillis }.map { row ->
            when (row.header.id) {
                in unreadable -> ArchiveEntry.Unreadable(row.header)
                in unknown -> ArchiveEntry.Unknown(row.header)
                else -> ArchiveEntry.Readable(row.header, row.payload.copyOf())
            }
        }
    }

    override suspend fun write(rows: List<ArchiveRow>) = change("write") {
        transactions += rows.map { it.header.id }
        for (row in rows) {
            if (row.header.open) closeOthers(row.header.id)
            this.rows[row.header.id] = row
        }
    }

    override suspend fun delete(id: String) = change("delete") { remove(id) }

    override suspend fun deleteAll() = change("deleteAll") {
        rows.clear()
        unreadable.clear()
        unknown.clear()
        keyDeleted = true
    }

    override suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int = change("deleteOutside") {
        val gone = rows.values.filter { !it.header.open && (it.header.lastMillis < oldestMillis || it.header.lastMillis > newestMillis) }
        for (row in gone) remove(row.header.id)
        gone.size
    }

    /** The payload of row [id] as text. */
    fun payload(id: String): String = rows.getValue(id).payload.decodeToString()

    private fun <T> change(name: String, block: () -> T): T {
        calls += name
        val result = block()
        interruptions.remove(name)?.let { throw it }
        return result
    }

    private fun closeOthers(id: String) {
        rows.replaceAll { other, row -> if (other != id && row.header.open) ArchiveRow(row.header.copy(open = false), row.payload) else row }
    }

    private fun remove(id: String) {
        rows.remove(id)
        unreadable.remove(id)
        unknown.remove(id)
    }

    override fun toString(): String = "MemoryArchive(rows=${rows.size})"
}
