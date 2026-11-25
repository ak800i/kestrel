package dev.kestrel.conversation.testing

import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveHeader
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.RecordArchive

/**
 * The archive in memory, for the JVM tests: the rows by id, the ids whose payload fails its tag ([unreadable]) or is in a
 * format that this version cannot read ([unknown]), each write's ids in order ([transactions]), how many rows were read,
 * and whether the key was deleted.
 */
class MemoryArchive : RecordArchive {
    val rows = LinkedHashMap<String, ArchiveRow>()
    val unreadable = mutableSetOf<String>()
    val unknown = mutableSetOf<String>()
    val transactions = mutableListOf<List<String>>()
    var reads = 0
    var keyDeleted = false

    override suspend fun headers(): List<ArchiveHeader> = rows.values.map { it.header }.sortedByDescending { it.lastMillis }

    override suspend fun read(id: String): ArchiveEntry? {
        reads++
        val row = rows[id] ?: return null
        return when (id) {
            in unreadable -> ArchiveEntry.Unreadable(row.header)
            in unknown -> ArchiveEntry.Unknown(row.header)
            else -> ArchiveEntry.Readable(row.header, row.payload.copyOf())
        }
    }

    override suspend fun write(rows: List<ArchiveRow>) {
        transactions += rows.map { it.header.id }
        for (row in rows) this.rows[row.header.id] = row
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        unreadable.remove(id)
        unknown.remove(id)
    }

    override suspend fun deleteAll() {
        rows.clear()
        unreadable.clear()
        unknown.clear()
        keyDeleted = true
    }

    override suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int {
        val gone = rows.values.filter { !it.header.open && (it.header.lastMillis < oldestMillis || it.header.lastMillis > newestMillis) }
        for (row in gone) delete(row.header.id)
        return gone.size
    }

    /** The payload of row [id] as text. */
    fun payload(id: String): String = rows.getValue(id).payload.decodeToString()

    override fun toString(): String = "MemoryArchive(rows=${rows.size})"
}
