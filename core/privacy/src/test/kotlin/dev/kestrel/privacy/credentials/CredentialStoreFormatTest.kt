package dev.kestrel.privacy.credentials

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.CredentialsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator

class CredentialStoreFormatTest {
    private val dao = MapCredentialsDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val unreadable = mutableListOf<String>()
    private val store = CredentialStore(
        dao,
        AesGcmFieldCipher { key },
        onUnreadable = { unreadable += it },
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun keepsACredentialInAnUnknownFormatAndReportsItOnceUntilItIsReplaced() = runTest {
        store.put("profile-1", byteArrayOf(1, 2, 3))
        val payload = dao.rows.getValue("profile-1").payload
        // A later version's format: AesGcmFieldCipher reads only version 1.
        dao.rows["profile-1"] = CredentialRow("profile-1", byteArrayOf(2) + payload.copyOfRange(1, payload.size))

        assertNull(store.get("profile-1"))
        assertNull(store.get("profile-1"))
        assertEquals(listOf("profile-1"), dao.ids())
        assertEquals(listOf("profile-1"), unreadable)

        store.put("profile-1", byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), store.get("profile-1"))
        dao.rows["profile-1"] = CredentialRow("profile-1", byteArrayOf(9))
        assertNull(store.get("profile-1"))
        assertEquals(listOf("profile-1", "profile-1"), unreadable)
    }

    class MapCredentialsDao : CredentialsDao {
        val rows = sortedMapOf<String, CredentialRow>()

        override suspend fun get(id: String): CredentialRow? = rows[id]

        override suspend fun put(row: CredentialRow) {
            rows[row.id] = row
        }

        override suspend fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

        override suspend fun ids(): List<String> = rows.keys.toList()
    }
}
