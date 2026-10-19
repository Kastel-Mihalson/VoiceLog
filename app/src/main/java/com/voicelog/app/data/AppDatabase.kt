package com.voicelog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Section::class, LogEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voicelog.db"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        // При первом запуске создаём три примера разделов.
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(
                                "INSERT INTO sections (name, unit, keywords, askValue) " +
                                    "VALUES ('Траты', '₽', 'потратил,купил,расход,покупка', 1)"
                            )
                            db.execSQL(
                                "INSERT INTO sections (name, unit, keywords, askValue) " +
                                    "VALUES ('Отжимания', 'раз', 'отжался,подход', 1)"
                            )
                            db.execSQL(
                                "INSERT INTO sections (name, unit, keywords, askValue) " +
                                    "VALUES ('Таблетки', 'шт', 'таблетка,лекарство,принял', 0)"
                            )
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
