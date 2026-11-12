package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LogEntryRow::class], version = 1, exportSchema = true)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        const val FILE_NAME = "kestrel.db"

        fun create(context: Context): KestrelDatabase =
            Room.databaseBuilder(context, KestrelDatabase::class.java, FILE_NAME).build()
    }
}
