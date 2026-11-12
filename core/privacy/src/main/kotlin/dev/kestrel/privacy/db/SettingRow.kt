package dev.kestrel.privacy.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One setting, stored as encrypted JSON. */
@Entity(tableName = "settings")
class SettingRow(
    @PrimaryKey val name: String,
    val payload: ByteArray,
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE name = :name")
    suspend fun get(name: String): SettingRow?

    @Query("SELECT * FROM settings WHERE name = :name")
    fun observe(name: String): Flow<SettingRow?>

    @Upsert
    suspend fun put(row: SettingRow)
}
