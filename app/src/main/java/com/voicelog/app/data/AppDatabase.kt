package com.voicelog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

// ВАЖНО: если когда-нибудь менять структуру таблиц, нужно увеличить version и написать
// Migration (addMigrations). Нельзя использовать fallbackToDestructiveMigration —
// он стирает все записи пользователя при обновлении.
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
                        // При самой первой установке создаём один раздел — «Траты».
                        // Остальные пользователь создаёт сам (в «Записи» или голосом).
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(
                                "INSERT INTO sections (name, unit, keywords, askValue) " +
                                    "VALUES ('Траты', '₽', 'потратил,купил,расход,покупка', 1)"
                            )
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
