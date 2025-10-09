package com.localai.assistant.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for storing messages in local database
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val content: String,
    val role: String,  // USER, ASSISTANT, SYSTEM
    val timestamp: Long,
    val attachments: String  // JSON string of attachments
)
