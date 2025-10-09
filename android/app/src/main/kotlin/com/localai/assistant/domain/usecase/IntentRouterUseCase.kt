package com.localai.assistant.domain.usecase

import com.localai.assistant.domain.model.AttachmentType
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.ModelType
import javax.inject.Inject

/**
 * Use case for routing user intent to the appropriate model
 * Implements intelligent routing logic
 */
class IntentRouterUseCase @Inject constructor() {

    companion object {
        // Keywords that indicate document/vision tasks
        private val DOCUMENT_KEYWORDS = setOf(
            "invoice", "receipt", "pdf", "document", "scan",
            "extract", "read", "ocr", "form", "paper",
            "table", "chart", "graph", "image", "photo",
            "picture", "screenshot"
        )
    }

    /**
     * Route a message to the appropriate model
     * @return The model type that should handle this message
     */
    fun routeIntent(message: Message): ModelType {
        // If there's an image/document attachment, use vision model
        if (message.attachments.any {
            it.type == AttachmentType.IMAGE || it.type == AttachmentType.DOCUMENT
        }) {
            return ModelType.VISION_DOCUMENT
        }

        // Check for document-related keywords in the message
        val contentLower = message.content.lowercase()
        if (DOCUMENT_KEYWORDS.any { keyword -> keyword in contentLower }) {
            return ModelType.VISION_DOCUMENT
        }

        // Default to general text model for conversation/Q&A
        return ModelType.TEXT_GENERAL
    }
}
