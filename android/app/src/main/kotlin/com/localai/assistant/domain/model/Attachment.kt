package com.localai.assistant.domain.model

/**
 * Represents a file or image attachment to a message
 */
data class Attachment(
    val id: String,
    val type: AttachmentType,
    val uri: String,
    val mimeType: String
)

enum class AttachmentType {
    IMAGE,
    DOCUMENT,
    AUDIO
}
