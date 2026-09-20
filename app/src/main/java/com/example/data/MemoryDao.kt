package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<MemoryEntity>

    @Query("DELETE FROM memories")
    suspend fun clearAll()

    @Query("DELETE FROM memories WHERE category IN ('CHAT_USER', 'CHAT_JARVIS')")
    suspend fun clearChatMemories()

    @Query("DELETE FROM memories WHERE category = :category")
    suspend fun clearCategory(category: String)
}
