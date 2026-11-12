package dev.kestrel.privacy.settings

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator

class SettingsStoreTest {
    @Serializable
    data class Volume(val level: Int)

    private val dao = FakeSettingsDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val unreadable = mutableListOf<String>()
    private val store = SettingsStore(
        dao,
        AesGcmFieldCipher { key },
        onUnreadable = { unreadable += it },
        dispatcher = Dispatchers.Unconfined,
    )
    private val volume = SettingKey("volume", Volume.serializer(), Volume(level = 5))
    private val otherVolume = SettingKey("other-volume", Volume.serializer(), Volume(level = 1))

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

    @Test
    fun runsTheCipherOnTheStoreDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val cipher = AesGcmFieldCipher { key.also { threads += Thread.currentThread() } }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = SettingsStore(dao, cipher, dispatcher = dispatcher)
            store.set(volume, Volume(level = 9))
            store.get(volume)
            store.observe(volume).first()

            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    @Test
    fun deletesAValueCopiedFromAnotherSettingAndUsesTheDefault() = runTest {
        store.set(otherVolume, Volume(level = 2))
        dao.rows.value += "volume" to SettingRow("volume", dao.rows.value.getValue("other-volume").payload)

        assertEquals(Volume(5), store.get(volume))
        assertFalse(dao.rows.value.containsKey("volume"))
        assertEquals(listOf("volume"), unreadable)
    }

    @Test
    fun keepsAValueInAnUnknownFormatAndUsesTheDefault() = runTest {
        val newerFormat = AesGcmFieldCipher { key }.encrypt("""{"level":"loud"}""".encodeToByteArray(), "settings/volume".encodeToByteArray())
        dao.rows.value += "volume" to SettingRow("volume", newerFormat)

        assertEquals(Volume(5), store.get(volume))
        assertEquals(Volume(5), store.observe(volume).first())
        assertTrue(dao.rows.value.containsKey("volume"))
        assertEquals(listOf("volume"), unreadable)
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

    override suspend fun delete(name: String): Int {
        val existed = name in rows.value
        rows.value = rows.value - name
        return if (existed) 1 else 0
    }
}
