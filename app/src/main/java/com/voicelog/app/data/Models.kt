package com.voicelog.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Раздел, созданный пользователем: «Траты», «Игры», «Спорт»… */
@Entity(tableName = "sections")
data class Section(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Единица измерения: ₽, раз, шт, мин… */
    val unit: String,
    /** Слова-подсказки для распознавания через запятую. */
    val keywords: String,
    /** Если число не названо — переспрашивать (true) или считать за 1 (false). */
    val askValue: Boolean = true,
    /** Тип раздела: NUMBERS — суммы/количества со статистикой, NOTES — короткие заметки. */
    @ColumnInfo(defaultValue = "'NUMBERS'")
    val type: String = SectionType.NUMBERS.name
)

enum class SectionType { NUMBERS, NOTES }

/** Раздел с заметками (а не с суммами и количествами). */
val Section.isNotes: Boolean get() = type == SectionType.NOTES.name

/**
 * Запись в разделе. Значение хранится в сотых долях (254,55 ₽ -> 25455),
 * чтобы суммы не «плыли» из-за округления.
 */
@Entity(
    tableName = "log_entries",
    foreignKeys = [
        ForeignKey(
            entity = Section::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sectionId")]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sectionId: Long,
    val description: String,
    val valueCents: Long,
    /** Дата и время записи, миллисекунды. */
    val timestamp: Long
)
