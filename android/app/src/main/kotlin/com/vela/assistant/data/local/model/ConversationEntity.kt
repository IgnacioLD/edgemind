package com.vela.assistant.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for storing conversations
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)
