package com.localai.assistant.domain.repository

import com.localai.assistant.domain.model.InferenceRequest
import com.localai.assistant.domain.model.InferenceResult
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun runInference(request: InferenceRequest): Flow<InferenceResult>
}
