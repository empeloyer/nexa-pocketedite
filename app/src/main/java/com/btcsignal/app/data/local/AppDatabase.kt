package com.btcsignal.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SignalEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "btc_signal.db"
            )
                // Version bumped 1 -> 2 for the AI fields added to SignalEntity (see
                // SignalEntity.kt). No Migration is written because this project has not
                // shipped/compiled yet (see README.md) - there is no installed version 1
                // database to preserve. destructive fallback wipes local signal history
                // on upgrade; if this app is ever shipped to real users before this point,
                // replace this with a real Migration(1, 2) that ADDs the new nullable
                // columns instead (SQLite ALTER TABLE ADD COLUMN, one per new field).
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
