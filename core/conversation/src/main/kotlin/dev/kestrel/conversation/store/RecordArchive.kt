package dev.kestrel.conversation.store

/**
 * The conversations' rows as the store keeps them (design 9.1 and 9.2 of sub-project 7): plain header fields for sorting
 * and retention, and a payload that only [ArchivedConversationStore] reads. `:app`'s `RoomRecordArchive` (plan 7C)
 * implements it with `:core:privacy`'s `ConversationArchiveStore`, which encrypts each payload under the Keystore key
 * `kestrel.conversations` with the associated data `conversations/<id>`; the JVM tests use the fixtures' `MemoryArchive`.
 */
interface RecordArchive {
    /**
     * Every row in one call, the newest change first, so that the first use of the store is one query: each row as
     * [ArchiveEntry.Readable], [ArchiveEntry.Unreadable] when its payload failed its tag, or [ArchiveEntry.Unknown] when its
     * format is newer.
     */
    suspend fun readAll(): List<ArchiveEntry>

    /** Inserts or replaces [rows], all in one transaction. */
    suspend fun write(rows: List<ArchiveRow>)

    suspend fun delete(id: String)

    /** Deletes every row, the open one too, and the archive's key; a new key is made at the next write. */
    suspend fun deleteAll()

    /** Deletes the closed rows whose last change is before [oldestMillis] or after [newestMillis]; returns how many. */
    suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int
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
