package com.vela.assistant.domain.model

/**
 * Result from AI model inference
 */
sealed class InferenceResult {
    data class Success(
        val text: String,
        val tokensGenerated: Int,
        val inferenceTimeMs: Long
    ) : InferenceResult()

    data class Streaming(
        val text: String,  // Token being streamed
        val tokensGenerated: Int
    ) : InferenceResult()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : InferenceResult()

    object Loading : InferenceResult()
}
