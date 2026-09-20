package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String, // "CHAT", "ROUTINE", "PREFERENCE", "NOTE", "FACT"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
