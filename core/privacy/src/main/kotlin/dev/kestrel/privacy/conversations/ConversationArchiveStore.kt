package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

/** A conversation to store: its plain fields and its payload, which the archive encrypts. toString prints only sizes. */
class PlainConversation(val header: ConversationHeader, val payload: ByteArray) {
    override fun toString(): String = "PlainConversation(header=$header, payload=${payload.size} bytes)"
}

/** A conversation as the archive reads it back. */
sealed interface StoredConversation {
    val header: ConversationHeader

    /** Its payload, decrypted. */
    class Readable(override val header: ConversationHeader, val payload: ByteArray) : StoredConversation {
        override fun toString(): String = "Readable(header=$header, payload=${payload.size} bytes)"
    }

    /** Its payload failed its check: it was moved from another row, damaged, or its key was erased. It can never be read. */
    class FailedCheck(override val header: ConversationHeader) : StoredConversation

    /** Its payload is in a format that this version of Kestrel cannot read; the row stays for a later version. */
    class UnknownFormat(override val header: ConversationHeader) : StoredConversation
}

/**
 * The archive of past conversations (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose payload is
 * encrypted under its own erasable key and bound to the conversation's id, so that a payload cannot be moved to another
 * row. It knows only bytes: `:app` gives it the conversation's JSON. Only the id, the open and last-change times, whether
 * the conversation is open and its number of commands are plain, for sorting and retention.
 *
 * It deletes no row on its own: plan 7B's store deletes a conversation that fails its check, and reports it. A
 * conversation in a format that this version cannot read is kept, and [onUnknownFormat] hears of it once per process; when
 * [onUnknownFormat] throws, the read fails, and the next read of the conversation reports it again.
 *
 * Every function runs under the key's [ErasableKey.guard], which every user of the key shares, so that [deleteAll] never
 * erases the key between the encryption of a write and its row, which would leave a row that no key opens. The functions are safe to call from the main thread, because they
 * encrypt, decrypt and query on [dispatcher].
 */
class ConversationArchiveStore(
    private val dao: ConversationDao,
    private val key: ErasableKey,
    private val onUnknownFormat: suspend () -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cipher = AesGcmFieldCipher { key.get() }

    // The ids of the rows in an unknown format whose report was made, so that each is reported once per process; a report
    // that failed or was cancelled is made again at the next read. Used only under the key's guard.
    private val reportedUnknown = mutableSetOf<String>()

    /** Every conversation's plain fields, the newest last change first. */
    suspend fun headers(): List<ConversationHeader> = locked { dao.headers() }

    /** The conversation [id], or null when there is none. */
    suspend fun read(id: String): StoredConversation? = locked { dao.get(id)?.let { stored(it) } }

    /**
     * Stores [conversations], each replacing its earlier row, in one transaction. Before an open one is stored, every other
     * open conversation closes, so that at most one is open (design 6.1 of sub-project 7).
     */
    suspend fun write(conversations: List<PlainConversation>) {
        locked {
            dao.write(
                conversations.map {
                    val header = it.header
                    ConversationRow(header.id, header.openedMillis, header.lastMillis, header.open, header.commands, cipher.encrypt(it.payload, associatedData(header.id)))
                },
            )
            conversations.forEach { reportedUnknown.remove(it.header.id) }
        }
    }

    suspend fun delete(id: String) {
        locked {
            dao.delete(id)
            reportedUnknown.remove(id)
        }
    }

    /**
     * "Delete history" (design 9.7 of sub-project 7): deletes every conversation, the open one too, and then erases the key,
     * so that no copy of the database file can be read afterwards. The next write makes a new key.
     */
    suspend fun deleteAll() {
        locked {
            dao.deleteAll()
            key.erase()
            reportedUnknown.clear()
        }
    }

    /**
     * Deletes the closed conversations whose last change is before [oldestMillis] or after [newestMillis], and returns how
     * many it deleted; the open conversation stays (design 9.3 of sub-project 7).
     */
    suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int = locked { dao.deleteOutside(oldestMillis, newestMillis) }

    private suspend fun stored(row: ConversationRow): StoredConversation {
        val header = ConversationHeader(row.id, row.openedMillis, row.lastMillis, row.open, row.commands)
        return try {
            StoredConversation.Readable(header, cipher.decrypt(row.payload, associatedData(row.id)))
        } catch (e: AEADBadTagException) {
            StoredConversation.FailedCheck(header)
        } catch (e: IllegalArgumentException) {
            if (row.id !in reportedUnknown) {
                onUnknownFormat()
                reportedUnknown += row.id
            }
            StoredConversation.UnknownFormat(header)
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = key.guard.withLock { withContext(dispatcher) { block() } }

    private fun associatedData(id: String) = "conversations/$id".encodeToByteArray()
}
