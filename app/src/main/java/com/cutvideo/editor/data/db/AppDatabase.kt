package com.cutvideo.editor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, MediaAssetEntity::class, ExportEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun mediaDao(): MediaDao
    abstract fun exportDao(): ExportDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cutvideo.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
