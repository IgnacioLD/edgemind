package com.vela.assistant.data.repository

import com.vela.assistant.data.local.Gemma4ModelWrapper
import com.vela.assistant.data.local.ModelFileManager
import com.vela.assistant.domain.model.InferenceRequest
import com.vela.assistant.domain.model.InferenceResult
import com.vela.assistant.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val wrapper: Gemma4ModelWrapper,
    private val fileManager: ModelFileManager,
) : ModelRepository {

    override fun runInference(request: InferenceRequest): Flow<InferenceResult> = flow {
        if (!fileManager.isModelPresent()) {
            emit(InferenceResult.Error("Gemma 4 model not downloaded yet."))
            return@flow
        }

        emit(InferenceResult.Loading)

        val started = System.currentTimeMillis()
        var tokenCount = 0
        try {
            wrapper.generate(request.prompt, request.audioPcm).collect { chunk ->
                when (chunk) {
                    is Gemma4ModelWrapper.Chunk.Token -> {
                        tokenCount++
                        emit(
                            InferenceResult.Streaming(
                                text = chunk.text,
                                tokensGenerated = tokenCount,
                            ),
                        )
                    }
                    is Gemma4ModelWrapper.Chunk.Done -> {
                        val elapsed = System.currentTimeMillis() - started
                        Timber.i("Generation complete: $tokenCount chunks in ${elapsed}ms")
                        emit(
                            InferenceResult.Success(
                                text = chunk.totalText,
                                tokensGenerated = tokenCount,
                                inferenceTimeMs = elapsed,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
            emit(InferenceResult.Error(e.message ?: "Inference failed", e))
        }
    }
        // Pin upstream + the file check + collect lambda to IO so they never run on Main when the
        // ChatViewModel collects on viewModelScope (Main.immediate). buffer() decouples the
        // producer from UI-side recompositions so a slow recompose can't backpressure the model.
        .flowOn(Dispatchers.IO)
        .buffer()
}
