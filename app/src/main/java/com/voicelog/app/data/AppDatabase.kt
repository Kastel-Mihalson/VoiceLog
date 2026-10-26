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
@Database(entities = [Section::class, LogEntry::class, Task::class], version = 3, exportSchema = false)
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

        /** Версия 3: добавлен список задач. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `doneAt` INTEGER)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voicelog.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(object : RoomDatabase.Callback() {
                        // При самой первой установке создаём один раздел — «Траты».
                        // Остальные пользователь создаёт сам (на страницах «Заметки» / «Статистика» или голосом).
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
