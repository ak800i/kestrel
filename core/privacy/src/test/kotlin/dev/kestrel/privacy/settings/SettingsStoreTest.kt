package dev.kestrel.privacy.settings

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.KeyGenerator

class SettingsStoreTest {
    @Serializable
    data class Volume(val level: Int)

    private val dao = FakeSettingsDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = SettingsStore(dao, AesGcmFieldCipher { key })
    private val volume = SettingKey("volume", Volume.serializer(), Volume(level = 5))

    @Test
    fun returnsTheDefaultUntilSet() = runTest {
        assertEquals(Volume(5), store.get(volume))
        assertEquals(Volume(5), store.observe(volume).first())
    }

    @Test
    fun returnsTheStoredValue() = runTest {
        store.set(volume, Volume(level = 9))

        assertEquals(Volume(9), store.get(volume))
        assertEquals(Volume(9), store.observe(volume).first())
    }

    @Test
    fun storesValuesEncrypted() = runTest {
        store.set(volume, Volume(level = 9))

        val stored = dao.rows.value.getValue("volume").payload.decodeToString(throwOnInvalidSequence = false)

        assertFalse(stored.contains("level"))
    }
}

/** Mimics the SQL in [SettingsDao]; KestrelDatabaseTest checks the real queries on a device. */
private class FakeSettingsDao : SettingsDao {
    val rows = MutableStateFlow<Map<String, SettingRow>>(emptyMap())

    override suspend fun get(name: String): SettingRow? = rows.value[name]

    override fun observe(name: String): Flow<SettingRow?> = rows.map { it[name] }

    override suspend fun put(row: SettingRow) {
        rows.value = rows.value + (row.name to row)
    }
}
