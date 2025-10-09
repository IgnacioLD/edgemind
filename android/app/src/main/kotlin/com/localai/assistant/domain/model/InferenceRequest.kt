package com.localai.assistant.domain.model

/**
 * Request for AI model inference
 */
data class InferenceRequest(
    val prompt: String,
    val modelType: ModelType,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val imageUri: String? = null
)
