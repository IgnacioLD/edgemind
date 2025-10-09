package com.localai.assistant.data.repository

import android.content.Context
import com.localai.assistant.data.local.ONNXModelWrapper
import com.localai.assistant.data.local.SimpleTokenizer
import com.localai.assistant.domain.model.InferenceRequest
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.ModelType
import com.localai.assistant.domain.repository.ModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ModelRepository
 * Manages ONNX models with NPU/GPU/NNAPI acceleration
 * Using Phi-3 mini (3.8B parameters, INT4 quantized to 2.6GB)
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ModelRepository {

    companion object {
        // Model file paths - Phi-3 is text-only
        private const val TEXT_MODEL_PATH = "models/phi3-mini-4k-instruct.onnx"
        private const val VISION_MODEL_PATH = TEXT_MODEL_PATH  // Same model for now
    }

    private val models = mutableMapOf<ModelType, ONNXModelWrapper>()
    private val tokenizer = SimpleTokenizer(context)

    override suspend fun initializeModel(modelType: ModelType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (models.containsKey(modelType)) {
                Timber.d("Model already loaded: $modelType")
                return@withContext Result.success(Unit)
            }

            // Initialize tokenizer first
            if (!tokenizer.isInitialized()) {
                Timber.d("Initializing tokenizer...")
                tokenizer.initialize()
                Timber.i("Tokenizer initialized (vocab size: ${tokenizer.getVocabSize()})")
            }

            val modelPath = when (modelType) {
                ModelType.TEXT_GENERAL -> TEXT_MODEL_PATH
                ModelType.VISION_DOCUMENT -> VISION_MODEL_PATH
            }

            val wrapper = ONNXModelWrapper(context, modelPath)
            val accelerationType = wrapper.initialize().getOrThrow()

            models[modelType] = wrapper

            // Log hardware info
            val hwInfo = wrapper.getHardwareInfo()
            Timber.i("Model initialized: $modelType with ${accelerationType.name}")
            Timber.d(hwInfo.toString())

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize model: $modelType")
            Result.failure(e)
        }
    }

    override fun runInference(request: InferenceRequest): Flow<InferenceResult> = flow {
        emit(InferenceResult.Loading)

        try {
            // Ensure model is loaded
            if (!isModelLoaded(request.modelType)) {
                initializeModel(request.modelType).getOrThrow()
            }

            val model = models[request.modelType]
                ?: throw IllegalStateException("Model not loaded: ${request.modelType}")

            val startTime = System.currentTimeMillis()

            // Step 1: Tokenize text prompt
            Timber.d("Tokenizing prompt: ${request.prompt.take(50)}...")
            val tokenized = tokenizer.encode(
                text = request.prompt,
                addSpecialTokens = true,
                maxLength = 4096  // Phi-3 supports 4k context
            )
            Timber.d("Token IDs: ${tokenized.inputIds.take(10).joinToString(", ")}... (${tokenized.inputIds.size} tokens)")

            // Step 2: Run inference
            Timber.d("Running inference on ${model.getAccelerationType().name}...")
            val logits = model.runInference(
                inputIds = tokenized.inputIds,
                attentionMask = tokenized.attentionMask
            )

            // Step 3: Decode output (greedy decoding)
            // Logits shape: [batch=1, seq_len, vocab_size=32064]
            // We take the last token's logits for next token prediction
            val vocabSize = 32064  // Phi-3 vocab size
            val seqLen = tokenized.inputIds.size

            // Get logits for the last position
            val lastTokenLogits = if (logits.size >= vocabSize) {
                val startIdx = (seqLen - 1) * vocabSize
                if (startIdx + vocabSize <= logits.size) {
                    logits.sliceArray(startIdx until startIdx + vocabSize)
                } else {
                    logits.sliceArray(logits.size - vocabSize until logits.size)
                }
            } else {
                logits
            }

            // Greedy sampling: pick token with highest probability
            val nextTokenId = lastTokenLogits.indices.maxByOrNull { lastTokenLogits[it] } ?: 0

            Timber.d("Generated next token ID: $nextTokenId")

            // Decode token
            val outputText = tokenizer.decode(longArrayOf(nextTokenId.toLong()), skipSpecialTokens = true)

            val inferenceTime = System.currentTimeMillis() - startTime

            Timber.i("Text inference complete in ${inferenceTime}ms")

            emit(
                InferenceResult.Success(
                    text = outputText,
                    tokensGenerated = 1,  // Single token for now
                    inferenceTimeMs = inferenceTime
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
            emit(InferenceResult.Error(e.message ?: "Unknown error", e))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun isModelLoaded(modelType: ModelType): Boolean {
        return models.containsKey(modelType)
    }

    override suspend fun unloadModel(modelType: ModelType): Result<Unit> {
        return try {
            models[modelType]?.close()
            models.remove(modelType)
            Timber.i("Model unloaded: $modelType")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to unload model: $modelType")
            Result.failure(e)
        }
    }
}
