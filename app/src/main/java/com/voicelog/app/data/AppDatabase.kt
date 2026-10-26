package com.voicelog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ВАЖНО: при любом изменении структуры таблиц нужно увеличить version и написать Migration
// (addMigrations). Нельзя использовать fallbackToDestructiveMigration —
// он стирает все записи пользователя при обновлении.
@Database(entities = [Section::class, LogEntry::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Версия 2: у разделов появился тип (числа или заметки). Все прежние разделы — «числа». */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sections ADD COLUMN type TEXT NOT NULL DEFAULT 'NUMBERS'")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voicelog.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(object : RoomDatabase.Callback() {
                        // При самой первой установке создаём один раздел — «Траты».
                        // Остальные пользователь создаёт сам (в «Записи» или голосом).
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(
                                "INSERT INTO sections (name, unit, keywords, askValue, type) " +
                                    "VALUES ('Траты', '₽', 'потратил,купил,расход,покупка', 1, 'NUMBERS')"
                            )
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
