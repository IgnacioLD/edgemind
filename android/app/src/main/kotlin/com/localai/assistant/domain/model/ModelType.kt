package com.localai.assistant.domain.model

/**
 * Enum representing different AI model types
 */
enum class ModelType {
    /**
     * Text-only model for general Q&A and conversation
     * (e.g., Phi-3-mini 3.8B)
     */
    TEXT_GENERAL,

    /**
     * Vision model for document understanding and image analysis
     * (e.g., Granite Docling 258M)
     */
    VISION_DOCUMENT
}
