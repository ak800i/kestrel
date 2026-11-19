package dev.kestrel.app.providers

import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.CredentialsDao
import dev.kestrel.providers.credentials.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator

class StoredCredentialsTest {
    private val dao = MapCredentialsDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = CredentialStore(dao, AesGcmFieldCipher { key }, dispatcher = Dispatchers.Unconfined)
    private val vault = StoredCredentials(store)

    @Test
    fun aCredentialReadsBackUntilItIsDeleted() = runTest {
        val token = Credential.OAuthToken(access = "test-access", refresh = "test-refresh", expiresAtMillis = 1_000)

        vault.write("test-1", token)

        assertEquals(token, vault.read("test-1"))
        vault.delete("test-1")
        assertNull(vault.read("test-1"))
    }

    @Test
    fun bytesThatAreNotACredentialReadAsNullAndTheRowIsKept() = runTest {
        store.put("test-1", "not a credential".encodeToByteArray())

        assertNull(vault.read("test-1"))
        assertEquals(listOf("test-1"), dao.ids())
    }

    private class MapCredentialsDao : CredentialsDao {
        val rows = sortedMapOf<String, CredentialRow>()

        override suspend fun get(id: String): CredentialRow? = rows[id]

        override suspend fun put(row: CredentialRow) {
            rows[row.id] = row
        }

        override suspend fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

        override suspend fun ids(): List<String> = rows.keys.toList()
    }
}
