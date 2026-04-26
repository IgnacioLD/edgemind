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
    val imageUri: String? = null,  // For document images (Granite Docling)
    // When the user's message was a voice recording, these carry duration + a downsampled
    // amplitude envelope (≈30 buckets, 0..1) for waveform rendering. Transient — not persisted.
    val voiceDurationMs: Long? = null,
    val voiceWaveform: List<Float>? = null,
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
