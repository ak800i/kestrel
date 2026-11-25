package dev.kestrel.conversation.testing

import dev.kestrel.conversation.store.ArchiveChange
import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveErasure
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.IndexStamp
import dev.kestrel.conversation.store.IndexWrite
import dev.kestrel.conversation.store.PastIndex
import dev.kestrel.conversation.store.PastIndexCodec
import dev.kestrel.conversation.store.RecordArchive
import dev.kestrel.conversation.store.ShardRead
import kotlinx.coroutines.CompletableDeferred

/**
 * The archive in memory, for the JVM tests: the rows by id, the ids whose payload fails its tag ([unreadable]) or is in a
 * format that this version cannot read ([unknown]), the index's shards by name ([shards]) and those that fail their check
 * ([unreadableShards]), each write's rows in order ([transactions]), the port's calls by name in order ([calls]), and
 * whether the key was erased. Its writes keep at most one row open, as plan 7C's table does. A change named in
 * [interruptions] (`write`, `purge`) is made and then throws its exception, once, as a Room call does when its
 * coroutine is cancelled while the call runs. A call named in [failures] throws its exception before it reads or changes
 * anything, for as long as it is set. While [readShardsGate] is set, `readShards` waits for it before it reads; while
 * [closedIdsGate] is set, `closedIds` lists the ids and then waits for it; while [readGate] is set, `read` reads the rows
 * and then waits for it, as a rebuild's read that later changes overtake. A row that a write stores is readable again.
 * While [keyKept] is set, `deleteAll` deletes the rows and the index but cannot erase the key, as when Keystore fails.
 *
 * After every write that stores shards, it checks what the store's design rests on (design 9.2): the shards decode, their
 * stamp is the table's, and no open row is in them; a write that breaks it fails the test.
 */
class MemoryArchive : RecordArchive {
    val rows = LinkedHashMap<String, ArchiveRow>()
    val unreadable = mutableSetOf<String>()
    val unknown = mutableSetOf<String>()
    val shards = LinkedHashMap<String, ByteArray>()
    val unreadableShards = mutableSetOf<String>()
    val transactions = mutableListOf<List<String>>()
    val calls = mutableListOf<String>()
    val interruptions = mutableMapOf<String, Throwable>()
    val failures = mutableMapOf<String, Throwable>()
    var readShardsGate: CompletableDeferred<Unit>? = null
    var closedIdsGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    var keyErased = false
    var keyKept = false

    override suspend fun readOpen(): ArchiveEntry? {
        calls += "readOpen"
        return rows.values.firstOrNull { it.header.open }?.let(::entry)
    }

    override suspend fun readShards(): List<ShardRead> {
        calls += "readShards"
        failures["readShards"]?.let { throw it }
        readShardsGate?.await()
        return shards.map { (name, bytes) -> if (name in unreadableShards) ShardRead.Unreadable(name) else ShardRead.Readable(name, bytes.copyOf()) }
    }

    override suspend fun readStamp(): IndexStamp {
        calls += "readStamp"
        failures["readStamp"]?.let { throw it }
        return stamp()
    }

    override suspend fun read(ids: Collection<String>): List<ArchiveEntry> {
        calls += "read"
        failures["read"]?.let { throw it }
        val read = ids.mapNotNull { rows[it] }.map(::entry)
        readGate?.await()
        return read
    }

    override suspend fun closedIds(): List<String> {
        calls += "closedIds"
        failures["closedIds"]?.let { throw it }
        val ids = rows.values.filter { !it.header.open }.sortedBy { it.header.lastMillis }.map { it.header.id }
        closedIdsGate?.await()
        return ids
    }

    override suspend fun write(change: ArchiveChange) = changing("write", change.index) {
        transactions += change.rows.map { it.header.id }
        for (id in change.deleted) remove(id)
        for (row in change.rows) {
            if (row.header.open) closeOthers(row.header.id)
            remove(row.header.id)
            rows[row.header.id] = row
        }
    }

    override suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int = changing("purge", index) {
        val gone = rows.values.filter { !it.header.open && (it.header.lastMillis < oldest || it.header.lastMillis > newest) }.map { it.header.id }
        gone.forEach(::remove)
        gone.size
    }

    override suspend fun deleteAll(): ArchiveErasure = changing("deleteAll", IndexWrite.Replace(emptyMap())) {
        rows.clear()
        unreadable.clear()
        unknown.clear()
        if (keyKept) {
            ArchiveErasure.KEY_KEPT
        } else {
            keyErased = true
            ArchiveErasure.KEY_ERASED
        }
    }

    /** The payload of row [id] as text. */
    fun payload(id: String): String = rows.getValue(id).payload.decodeToString()

    /** The index as the store last wrote it, decoded; null when there is none. */
    fun past(): PastIndex? = if (shards.isEmpty()) null else PastIndex.restored(shards.map { (name, bytes) -> PastIndexCodec.decode(name.toInt(), bytes) })

    /** What the table holds of closed rows, as plan 7C's query counts it. */
    fun stamp(): IndexStamp {
        val closed = rows.values.filter { !it.header.open }.map { it.header.lastMillis }
        return IndexStamp(closed.size, closed.maxOrNull() ?: 0, closed.sum())
    }

    private fun entry(row: ArchiveRow): ArchiveEntry = when (row.header.id) {
        in unreadable -> ArchiveEntry.Unreadable(row.header)
        in unknown -> ArchiveEntry.Unknown(row.header)
        else -> ArchiveEntry.Readable(row.header, row.payload.copyOf())
    }

    private fun <T> changing(name: String, index: IndexWrite, block: () -> T): T {
        calls += name
        failures[name]?.let { throw it }
        val result = block()
        when (index) {
            IndexWrite.Keep -> Unit
            is IndexWrite.Update -> {
                index.deleted.forEach { shards.remove(it); unreadableShards.remove(it) }
                index.put.forEach { (name, bytes) -> shards[name] = bytes.copyOf(); unreadableShards.remove(name) }
            }
            is IndexWrite.Replace -> {
                shards.clear()
                unreadableShards.clear()
                index.shards.forEach { (name, bytes) -> shards[name] = bytes.copyOf() }
            }
        }
        if (index is IndexWrite.Update || (index is IndexWrite.Replace && index.shards.isNotEmpty())) checkInStep()
        interruptions.remove(name)?.let { throw it }
        return result
    }

    // Design 9.2: an index that the store writes is in step with the table and never holds the open conversation.
    private fun checkInStep() {
        val index = past() ?: PastIndex.EMPTY
        check(index.stamp == stamp()) { "The store wrote an index whose stamp ${index.stamp} is not the table's ${stamp()}" }
        val open = rows.values.filter { it.header.open }.mapTo(HashSet()) { it.header.id }
        check(index.conversations.none { it.id.value in open } && index.kept.none { it.id in open }) { "The store wrote the open conversation into the index" }
    }

    private fun closeOthers(id: String) {
        rows.replaceAll { other, row -> if (other != id && row.header.open) ArchiveRow(row.header.copy(open = false), row.payload) else row }
    }

    private fun remove(id: String) {
        rows.remove(id)
        unreadable.remove(id)
        unknown.remove(id)
    }

    override fun toString(): String = "MemoryArchive(rows=${rows.size}, shards=${shards.size})"
}
