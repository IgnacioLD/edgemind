package com.localai.assistant.domain.repository

import com.localai.assistant.domain.model.InferenceRequest
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.ModelType
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for AI model operations
 * Following Clean Architecture, this is defined in the domain layer
 */
interface ModelRepository {
    /**
     * Initialize a specific model
     * @return Result indicating success or failure
     */
    suspend fun initializeModel(modelType: ModelType): Result<Unit>

    /**
     * Run inference on the model
     * @return Flow emitting inference results (supports streaming)
     */
    fun runInference(request: InferenceRequest): Flow<InferenceResult>

    /**
     * Check if a model is loaded and ready
     */
    suspend fun isModelLoaded(modelType: ModelType): Boolean

    /**
     * Unload a model from memory to free resources
     */
    suspend fun unloadModel(modelType: ModelType): Result<Unit>
}
