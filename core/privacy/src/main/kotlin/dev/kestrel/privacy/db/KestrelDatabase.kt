package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex

/**
 * Version 2 adds the table `credentials` (sub-project 4), and version 3 the tables `conversations` and
 * `conversation_index` (plan 7C of sub-project 7). Room migrates each earlier version by itself. A released version
 * never changes, because a phone that holds it could not open it any more: a change of an entity needs a new version
 * and its migration (`ReleasedSchemaTest` checks this against the released schemas).
 */
@Database(
    entities = [LogEntryRow::class, SettingRow::class, CredentialRow::class, ConversationRow::class, ConversationIndexRow::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao

    abstract fun credentialsDao(): CredentialsDao

    abstract fun conversationDao(): ConversationDao

    /**
     * The order of the logs' writes and of "delete history" in this database (design 9.7 of sub-project 7): an entry
     * whose write started before the history's deletion is deleted with it, and one that starts later stays. It is one
     * per database, so every store of the logs on it shares it.
     */
    val historyOrder = Mutex()

    companion object {
        const val FILE_NAME = "kestrel.db"

        @Volatile
        private var instance: KestrelDatabase? = null

        /**
         * The database of this process. Room tells observers only about writes made through the same
         * instance, so a second instance would hide changes from them.
         */
        fun get(context: Context): KestrelDatabase =
            instance ?: synchronized(this) {
                instance ?: build(Room.databaseBuilder(context.applicationContext, KestrelDatabase::class.java, FILE_NAME))
                    .also { instance = it }
            }

        /**
         * Builds [builder] as Kestrel's database is built, with [SecureDeletion]; the tests build theirs through it
         * too.
         */
        fun build(builder: RoomDatabase.Builder<KestrelDatabase>): KestrelDatabase = builder.addCallback(SecureDeletion).build()
    }
}

/**
 * Turns on SQLite's secure deletion when the database opens (design 9.1 and 9.7 of sub-project 7), so that a delete
 * overwrites the pages that it frees: an older copy of a shard, which every close replaces, or of a deleted
 * conversation or log entry is not left in the file's free pages. The pragma holds for one connection; a transaction
 * runs it on the one that writes, and so deletes. It does not rely on how the phone's SQLite was built, and it fails
 * the database's opening when SQLite does not turn it on, rather than keep deleted data readable.
 */
internal object SecureDeletion : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            val on = db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() && it.getInt(0) == 1 }
            check(on) { "SQLite did not turn on secure deletion" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
