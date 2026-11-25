package dev.kestrel.conversation.store

/**
 * The conversations' rows and the index of past conversations, as the store keeps them (design 9.1 and 9.2 of sub-project
 * 7): plain header fields for sorting and retention, a payload per conversation, and the index's shards, each in a row of
 * its own named by its number, which only [ArchivedConversationStore] reads. `:app`'s `RoomRecordArchive` (plan 7C) implements it with `:core:privacy`'s
 * `ConversationArchiveStore`, which encrypts each payload under the Keystore key `kestrel.conversations` with the associated
 * data `conversations/<id>`, and each shard under the same key with `conversation_index/<number>`, in a table of its own;
 * the JVM tests use the fixtures' `MemoryArchive`. Each read decrypts its rows on the thread that read them, one Keystore
 * operation per row (design R3).
 */
interface RecordArchive {
    /** The open conversation's row, or null: what a restore waits for, one decryption. */
    suspend fun readOpen(): ArchiveEntry?

    /** The index's shards, each decrypted, none when there is no index. */
    suspend fun readShards(): List<ShardRead>

    /** What the table holds of closed rows ([IndexStamp]), which an index that is in step counts too. */
    suspend fun readStamp(): IndexStamp

    /**
     * The rows [ids], which name each row once, in one call, each as its kind of entry, in the order of [ids], so that a
     * rebuild folds each page in the order of [closedIds]; an id without a row is left out.
     */
    suspend fun read(ids: Collection<String>): List<ArchiveEntry>

    /** The ids of the closed rows, the oldest last change first, without their payloads: what a rebuild reads in pages. */
    suspend fun closedIds(): List<String>

    /**
     * One transaction: deletes [ArchiveChange.deleted], inserts or replaces [ArchiveChange.rows], and changes the index's
     * shards as [ArchiveChange.index] says. Before an open row is stored, every other open row closes, so that at most one
     * is open, as plan 7C's table does.
     */
    suspend fun write(change: ArchiveChange)

    /**
     * One transaction: deletes every closed row whose last change is before [oldest] or after [newest] (design 9.3), whatever
     * the index lists, and changes the index's shards as [index] says; returns how many rows went.
     */
    suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int

    /**
     * "Delete history" (design 9.7): deletes every row, the open one too, and the index, in one transaction, and then erases
     * the archive's key, so that not even a copy of what was stored can be decrypted any more; a new key is made at the
     * next write. It throws only when the transaction did not commit, which leaves every row as it was. Once the transaction
     * has committed, the deletion is complete, and it returns whether the key went: [ArchiveErasure.KEY_KEPT] when it could
     * not be erased, which the archive's next change erases before it stores anything.
     */
    suspend fun deleteAll(): ArchiveErasure
}

/** What "delete history" did with the archive's key, once its transaction deleted every row and the index (design 9.7). */
enum class ArchiveErasure {
    KEY_ERASED,

    /** The key could not be erased; the archive's next change erases it before it stores anything. */
    KEY_KEPT,
}

/** A row's plain fields: the conversation's id, its open and last-change times, whether it is open, and its records. */
data class ArchiveHeader(val id: String, val openedMillis: Long, val lastMillis: Long, val open: Boolean, val commands: Int)

/** A row to write: its header and its payload, the snapshot's JSON as bytes. toString prints only sizes. */
class ArchiveRow(val header: ArchiveHeader, val payload: ByteArray) {
    override fun toString(): String = "ArchiveRow(header=$header, payload=${payload.size} bytes)"
}

/** A row as read: its payload, the mark that its payload failed its tag, or the mark that its format is one this version cannot read. */
sealed interface ArchiveEntry {
    val header: ArchiveHeader

    class Readable(override val header: ArchiveHeader, val payload: ByteArray) : ArchiveEntry {
        override fun toString(): String = "Readable(header=$header, payload=${payload.size} bytes)"
    }

    class Unreadable(override val header: ArchiveHeader) : ArchiveEntry {
        override fun toString(): String = "Unreadable(header=$header)"
    }

    /**
     * Its payload is in a format that this version of Kestrel cannot read; the row stays for a later version, and the
     * archive has reported it by its kind.
     */
    class Unknown(override val header: ArchiveHeader) : ArchiveEntry {
        override fun toString(): String = "Unknown(header=$header)"
    }
}

/** A shard's row as read, by its name (its number, as text): its bytes, or the mark that it cannot be read (its check failed, or its cipher's format is newer). */
sealed interface ShardRead {
    val name: String

    class Readable(override val name: String, val bytes: ByteArray) : ShardRead {
        override fun toString(): String = "Readable(${bytes.size} bytes)"
    }

    class Unreadable(override val name: String) : ShardRead {
        override fun toString(): String = "Unreadable"
    }
}

/** One change of the archive, in one transaction: rows to store, rows to delete, and what happens to the index. */
class ArchiveChange(val rows: List<ArchiveRow> = emptyList(), val deleted: Set<String> = emptySet(), val index: IndexWrite = IndexWrite.Keep) {
    override fun toString(): String = "ArchiveChange(rows=${rows.size}, deleted=${deleted.size}, index=$index)"
}

/**
 * What a change does to the index's shards, each named by its number: keeps them all; puts [Update.put], each replacing the
 * row of its name, and deletes [Update.deleted]; or deletes every shard and stores [Replace.shards] alone, so that a
 * `Replace` without shards leaves no index.
 */
sealed interface IndexWrite {
    data object Keep : IndexWrite

    class Update(val put: Map<String, ByteArray>, val deleted: Set<String>) : IndexWrite {
        override fun toString(): String = "Update(put=${put.size}, deleted=${deleted.size})"
    }

    class Replace(val shards: Map<String, ByteArray>) : IndexWrite {
        override fun toString(): String = "Replace(${shards.size} shards)"
    }
}
