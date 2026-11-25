package dev.kestrel.privacy

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.KestrelDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The database's migrations from each earlier version (design spec, section 13.4; design 9.2 of sub-project 7). Each test
 * uses a test-named database file of this library's own test app, so Kestrel's database is never touched.
 */
@RunWith(AndroidJUnit4::class)
class KestrelDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migrations = MigrationTestHelper(instrumentation, KestrelDatabase::class.java)

    @After
    fun deleteTheTestDatabase() {
        val context = instrumentation.targetContext
        context.deleteDatabase(MIGRATED)
        // Room's lock file beside the database, which deleteDatabase leaves.
        File("${context.getDatabasePath(MIGRATED).path}.lck").delete()
    }

    @Test
    fun version1MigratesToVersion2WithItsSettingsAndAnEmptyCredentialsTable() {
        migrations.createDatabase(MIGRATED, 1).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            close()
        }

        val migrated = migrations.runMigrationsAndValidate(MIGRATED, 2, true)

        assertArrayEquals(byteArrayOf(7), migrated.blob("SELECT payload FROM settings WHERE name = 'test-volume'"))
        assertEquals(0, migrated.count("credentials"))
    }

    @Test
    fun version2MigratesToVersion3WithItsRowsAndEmptyTablesOfConversationsAndTheirIndex() {
        migrations.createDatabase(MIGRATED, 2).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            execSQL("INSERT INTO credentials (id, payload) VALUES ('test-credential', X'08')")
            execSQL("INSERT INTO log_entries (log, timestamp_millis, payload) VALUES ('activity', 5, X'09')")
            close()
        }

        // Validation compares the migrated tables, their columns and the index on last_millis with the schema 3.json.
        val migrated = migrations.runMigrationsAndValidate(MIGRATED, 3, true)

        assertArrayEquals(byteArrayOf(7), migrated.blob("SELECT payload FROM settings WHERE name = 'test-volume'"))
        assertArrayEquals(byteArrayOf(8), migrated.blob("SELECT payload FROM credentials WHERE id = 'test-credential'"))
        assertArrayEquals(byteArrayOf(9), migrated.blob("SELECT payload FROM log_entries WHERE log = 'activity'"))
        assertEquals(0, migrated.count("conversations"))
        assertEquals(0, migrated.count("conversation_index"))
    }

    private fun SupportSQLiteDatabase.blob(sql: String): ByteArray =
        query(sql).use { rows ->
            rows.moveToFirst()
            rows.getBlob(0)
        }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { rows ->
            rows.moveToFirst()
            rows.getInt(0)
        }

    private companion object {
        const val MIGRATED = "test-migration.db"
    }
}
