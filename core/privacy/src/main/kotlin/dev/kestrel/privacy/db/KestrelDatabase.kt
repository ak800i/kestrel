package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 adds the table `credentials` (sub-project 4), and version 3 the table `conversations` (plan 7C of sub-project
 * 7). Room migrates each earlier version by itself.
 */
@Database(
    entities = [LogEntryRow::class, SettingRow::class, CredentialRow::class, ConversationRow::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao

    abstract fun credentialsDao(): CredentialsDao

    abstract fun conversationDao(): ConversationDao

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
                instance ?: Room.databaseBuilder(context.applicationContext, KestrelDatabase::class.java, FILE_NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
