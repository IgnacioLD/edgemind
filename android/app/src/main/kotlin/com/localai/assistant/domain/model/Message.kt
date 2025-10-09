package com.localai.assistant.domain.model

import java.util.UUID

/**
 * Domain model representing a chat message
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val role: MessageRole,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val imageUri: String? = null  // For document images (Granite Docling)
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
