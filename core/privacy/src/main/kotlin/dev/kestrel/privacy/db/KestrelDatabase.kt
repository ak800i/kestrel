package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LogEntryRow::class, SettingRow::class], version = 1, exportSchema = true)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao

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
