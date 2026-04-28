package com.vela.assistant.domain.repository

import com.vela.assistant.domain.model.InferenceRequest
import com.vela.assistant.domain.model.InferenceResult
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun runInference(request: InferenceRequest): Flow<InferenceResult>
}
