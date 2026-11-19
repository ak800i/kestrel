package dev.kestrel.privacy.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** One provider credential, encrypted with the credentials key (design spec, section 10.7). */
@Entity(tableName = "credentials")
class CredentialRow(
    @PrimaryKey val id: String,
    val payload: ByteArray,
)

@Dao
interface CredentialsDao {
    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun get(id: String): CredentialRow?

    @Upsert
    suspend fun put(row: CredentialRow)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT id FROM credentials ORDER BY id")
    suspend fun ids(): List<String>
}
