package com.localai.assistant.data.repository

import android.content.Context
import com.localai.assistant.data.local.ImagePreprocessor
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
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ModelRepository {

    companion object {
        // Model file paths in assets folder
        // Using Granite Docling for both text and vision (multimodal model)
        private const val TEXT_MODEL_PATH = "models/granite_docling.onnx"
        private const val VISION_MODEL_PATH = "models/granite_docling.onnx"
    }

    private val models = mutableMapOf<ModelType, ONNXModelWrapper>()
    private val tokenizer = SimpleTokenizer(context)
    private val imagePreprocessor = ImagePreprocessor(context)

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

            // Granite Docling requires both image and text prompt
            if (request.imageUri == null) {
                throw IllegalArgumentException("Granite Docling requires a document image. Please provide imageUri.")
            }

            // Step 1: Preprocess document image
            Timber.d("Preprocessing document image: ${request.imageUri}")
            val preprocessedImage = imagePreprocessor.preprocessImageFromUri(request.imageUri)
            Timber.d("Image preprocessed: ${preprocessedImage.pixelValues.size} floats [1, 1, 3, 512, 512]")

            // Step 2: Tokenize text prompt
            Timber.d("Tokenizing prompt: ${request.prompt.take(50)}...")
            val tokenized = tokenizer.encode(
                text = request.prompt,
                addSpecialTokens = true,
                maxLength = 512
            )
            Timber.d("Token IDs: ${tokenized.inputIds.take(10).joinToString(", ")}... (${tokenized.inputIds.size} tokens)")

            // Step 3: Run 3-model pipeline for document inference
            Timber.d("Running 3-model pipeline on ${model.getAccelerationType().name}...")
            val logits = model.runDocumentInference(
                pixelValues = preprocessedImage.pixelValues,
                pixelAttentionMask = preprocessedImage.pixelAttentionMask,
                inputIds = tokenized.inputIds,
                attentionMask = tokenized.attentionMask
            )

            // Step 4: Decode output (greedy decoding for DocTags markup)
            val vocabSize = tokenizer.getVocabSize()
            val outputTokens = mutableListOf<Long>()

            // Simple greedy decode to generate DocTags output
            for (i in logits.indices step vocabSize) {
                if (i + vocabSize <= logits.size) {
                    val slice = logits.sliceArray(i until i + vocabSize)
                    val maxIdx = slice.indices.maxByOrNull { slice[it] } ?: 0
                    outputTokens.add(maxIdx.toLong())
                }
            }

            Timber.d("Generated ${outputTokens.size} tokens (DocTags markup)")

            // Decode tokens to DocTags text
            val outputText = if (outputTokens.isNotEmpty()) {
                tokenizer.decode(outputTokens.toLongArray(), skipSpecialTokens = true)
            } else {
                "[Document processed but no structured output generated. Check model and tokenizer.]"
            }

            val inferenceTime = System.currentTimeMillis() - startTime

            Timber.i("Document processing complete in ${inferenceTime}ms")

            emit(
                InferenceResult.Success(
                    text = outputText,
                    tokensGenerated = outputTokens.size,
                    inferenceTimeMs = inferenceTime
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Document inference failed")
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
