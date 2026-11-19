package dev.kestrel.privacy.credentials

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.CredentialsDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException

/**
 * The providers' credentials, each one encrypted row bound to its ID (design spec, section 10.7): a row that is moved to
 * another ID does not decrypt. `:core:providers` gives the bytes (its CredentialCodec); this store never sees what they
 * hold. Its functions are safe to call from the main thread, because they encrypt, decrypt and query on [dispatcher].
 */
class CredentialStore(
    private val dao: CredentialsDao,
    private val cipher: FieldCipher,
    /** Called when a stored credential cannot be read. It gets only the ID, never the bytes. */
    private val onUnreadable: suspend (id: String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // The IDs of kept rows that were reported: a row in an unknown format is reported once per process, not at every read.
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Returns the credential's bytes, or null when there is none or it cannot be read. A row that fails authentication can
     * never be read again, so it is deleted; a row in a format that this version cannot read is kept for a later version,
     * and it is reported once until a new credential replaces it.
     */
    suspend fun get(id: String): ByteArray? =
        withContext(dispatcher) {
            val row = dao.get(id) ?: return@withContext null
            try {
                cipher.decrypt(row.payload, associatedData(id))
            } catch (e: AEADBadTagException) {
                dao.delete(id)
                onUnreadable(id)
                null
            } catch (e: IllegalArgumentException) {
                if (reported.add(id)) onUnreadable(id)
                null
            }
        }

    /** Stores [payload] under [id], replacing an earlier credential with the same ID. */
    suspend fun put(id: String, payload: ByteArray) {
        withContext(dispatcher) { dao.put(CredentialRow(id, cipher.encrypt(payload, associatedData(id)))) }
        reported.remove(id)
    }

    suspend fun delete(id: String) {
        withContext(dispatcher) { dao.delete(id) }
    }

    suspend fun ids(): List<String> = withContext(dispatcher) { dao.ids() }

    private fun associatedData(id: String) = "credentials/$id".encodeToByteArray()
}