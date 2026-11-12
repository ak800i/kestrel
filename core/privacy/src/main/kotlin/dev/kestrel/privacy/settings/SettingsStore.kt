package dev.kestrel.privacy.settings

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Names a setting, its JSON serializer and the value to use until the owner changes it. */
class SettingKey<T>(val name: String, val serializer: KSerializer<T>, val default: T)

/** Settings stored as encrypted JSON in Room (design spec, section 13.4). */
class SettingsStore(
    private val dao: SettingsDao,
    private val cipher: FieldCipher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun <T> get(key: SettingKey<T>): T = dao.get(key.name)?.let { decode(key, it) } ?: key.default

    fun <T> observe(key: SettingKey<T>): Flow<T> = dao.observe(key.name).map { row -> row?.let { decode(key, it) } ?: key.default }

    suspend fun <T> set(key: SettingKey<T>, value: T) {
        val plaintext = json.encodeToString(key.serializer, value).encodeToByteArray()
        dao.put(SettingRow(key.name, cipher.encrypt(plaintext, associatedData(key.name))))
    }

    private fun <T> decode(key: SettingKey<T>, row: SettingRow): T =
        json.decodeFromString(key.serializer, cipher.decrypt(row.payload, associatedData(key.name)).decodeToString())

    private fun associatedData(name: String) = "settings/$name".encodeToByteArray()
}
