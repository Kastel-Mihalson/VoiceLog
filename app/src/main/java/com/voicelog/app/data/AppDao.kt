package com.voicelog.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query("SELECT * FROM sections ORDER BY id")
    fun sections(): Flow<List<Section>>

    @Query("SELECT * FROM sections ORDER BY id")
    suspend fun sectionsOnce(): List<Section>

    @Query("SELECT * FROM sections WHERE id = :id")
    fun section(id: Long): Flow<Section?>

    @Insert
    suspend fun insertSection(section: Section): Long

    @Update
    suspend fun updateSection(section: Section)

    @Delete
    suspend fun deleteSection(section: Section)

    @Query("SELECT * FROM log_entries WHERE sectionId = :sectionId ORDER BY timestamp DESC")
    fun entries(sectionId: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
    fun allEntries(): Flow<List<LogEntry>>

    @Insert
    suspend fun insertEntry(entry: LogEntry): Long

    @Delete
    suspend fun deleteEntry(entry: LogEntry)
}
