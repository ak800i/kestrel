package dev.kestrel.privacy.settings

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException

/** Names a setting, its JSON serializer and the value to use until the owner changes it. */
class SettingKey<T>(val name: String, val serializer: KSerializer<T>, val default: T)

/** Settings stored as encrypted JSON in Room (design spec, section 13.4). */
class SettingsStore(
    private val dao: SettingsDao,
    private val cipher: FieldCipher,
    /** Called once per setting and process when a stored value cannot be read and its default is used instead. */
    private val onUnreadable: suspend (name: String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val reported = ConcurrentHashMap.newKeySet<String>()

    suspend fun <T> get(key: SettingKey<T>): T = valueOf(key, dao.get(key.name))

    fun <T> observe(key: SettingKey<T>): Flow<T> = dao.observe(key.name).map { row -> valueOf(key, row) }.distinctUntilChanged()

    suspend fun <T> set(key: SettingKey<T>, value: T) {
        val plaintext = json.encodeToString(key.serializer, value).encodeToByteArray()
        dao.put(SettingRow(key.name, cipher.encrypt(plaintext, associatedData(key.name))))
    }

    /**
     * Returns the stored value, or the default when nothing is stored or the stored value cannot be read.
     * A value that fails authentication can never be read again, so it is deleted. A value in a format
     * that this version cannot decode is kept, because a later version of Kestrel may read it.
     */
    private suspend fun <T> valueOf(key: SettingKey<T>, row: SettingRow?): T {
        if (row == null) return key.default
        return try {
            json.decodeFromString(key.serializer, cipher.decrypt(row.payload, associatedData(key.name)).decodeToString())
        } catch (e: AEADBadTagException) {
            dao.delete(key.name)
            report(key.name)
            key.default
        } catch (e: IllegalArgumentException) {
            // Also covers kotlinx.serialization's SerializationException, which extends IllegalArgumentException.
            report(key.name)
            key.default
        }
    }

    private suspend fun report(name: String) {
        if (reported.add(name)) onUnreadable(name)
    }

    private fun associatedData(name: String) = "settings/$name".encodeToByteArray()
}
