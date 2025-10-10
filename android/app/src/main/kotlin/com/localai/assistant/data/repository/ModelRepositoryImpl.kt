package com.localai.assistant.data.repository

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.localai.assistant.data.local.ONNXModelWrapper
import com.localai.assistant.data.local.Phi3BPETokenizer
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
    private val tokenizer = Phi3BPETokenizer(context)

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

            // Step 1: Format prompt with Phi-3 chat template
            val formattedPrompt = buildString {
                append("<|system|>\n")
                append("You are a helpful AI assistant. Answer questions accurately and concisely.")
                append("<|end|>\n")
                append("<|user|>\n")
                append(request.prompt)
                append("<|end|>\n")
                append("<|assistant|>\n")
            }

            // Step 2: Tokenize formatted prompt
            Timber.d("Tokenizing prompt: ${request.prompt.take(50)}...")
            val tokenized = tokenizer.encode(
                text = formattedPrompt,
                addSpecialTokens = true,
                maxLength = 4096  // Phi-3 supports 4k context
            )
            Timber.d("Token IDs: ${tokenized.inputIds.take(10).joinToString(", ")}... (${tokenized.inputIds.size} tokens)")

            // Step 2: Autoregressive generation loop WITH KV CACHE
            Timber.d("Starting text generation with KV cache on ${model.getAccelerationType().name}...")

            // Maximum tokens - let model decide when to stop via EOS tokens
            val maxNewTokens = 400  // Safety limit to prevent infinite loops

            val vocabSize = tokenizer.getVocabSize()
            val generatedTokens = mutableListOf<Long>()
            var kvCache: Map<String, OnnxTensor>? = null  // KV cache from previous iteration
            val fullAttentionMask = tokenized.attentionMask.toMutableList()

            // Advanced stopping criteria
            var consecutiveRepeats = 0  // Same token repeated
            var ngramRepetitions = 0  // N-gram pattern repetitions
            val tokenWindow = mutableListOf<Long>()  // Recent 20 tokens
            val ngramCache = mutableMapOf<List<Long>, Int>()  // Track n-gram frequencies
            var sentenceEndCount = 0  // Count sentence endings for short prompts

            // Generate tokens one by one
            for (i in 0 until maxNewTokens) {
                // For first iteration: process all input tokens
                // For subsequent: only process the NEW token (cache reused!)
                val inputIds = if (i == 0) {
                    tokenized.inputIds  // First: process full prompt
                } else {
                    longArrayOf(generatedTokens.last())  // Subsequent: only new token
                }

                // Run inference WITH KV cache
                val result = model.runInferenceWithCache(
                    inputIds = inputIds,
                    attentionMask = fullAttentionMask.toLongArray(),
                    pastKeyValues = kvCache  // Reuse cache from previous iteration
                )

                // Clean up OLD cache after getting new one (prevent memory leak)
                val oldCache = kvCache
                kvCache = result.presentKeyValues

                // Now close the old cache (it's been replaced)
                if (i > 0 && oldCache != null) {
                    oldCache.values.forEach { it.close() }
                }

                // Get logits for the last position
                val lastTokenLogits = if (result.logits.size >= vocabSize) {
                    val startIdx = (inputIds.size - 1) * vocabSize
                    if (startIdx + vocabSize <= result.logits.size) {
                        result.logits.sliceArray(startIdx until startIdx + vocabSize)
                    } else {
                        result.logits.sliceArray(result.logits.size - vocabSize until result.logits.size)
                    }
                } else {
                    result.logits
                }

                // Greedy sampling: pick token with highest probability
                val nextTokenId = lastTokenLogits.indices.maxByOrNull { lastTokenLogits[it] } ?: 0
                generatedTokens.add(nextTokenId.toLong())

                // Decode and emit the new token (streaming)
                val tokenText = tokenizer.decode(longArrayOf(nextTokenId.toLong()), skipSpecialTokens = true)
                Timber.d("Generated token $i: ID=$nextTokenId, text='$tokenText' (cached=${i > 0})")

                emit(
                    InferenceResult.Streaming(
                        text = tokenText,
                        tokensGenerated = generatedTokens.size
                    )
                )

                // ===== STOPPING CRITERIA =====

                // 1. EOS tokens: </s>=2, <|endoftext|>=32000, <|end|>=32007
                if (nextTokenId == 2 || nextTokenId == 32000 || nextTokenId == 32007) {
                    Timber.d("EOS token detected (ID=$nextTokenId), stopping generation")
                    break
                }

                // 2. Consecutive repetition detection (same token repeated many times)
                // Only stop if same token appears 10+ times in a row (severe repetition)
                // Note: generatedTokens already has nextTokenId added, so compare with second-to-last
                if (generatedTokens.size >= 2 && nextTokenId.toLong() == generatedTokens[generatedTokens.size - 2]) {
                    consecutiveRepeats++
                    if (consecutiveRepeats >= 10) {
                        Timber.d("Excessive consecutive repetition detected ($consecutiveRepeats times), stopping")
                        break
                    }
                } else {
                    consecutiveRepeats = 0
                }

                // 3. N-gram repetition detection (track 4-grams and 5-grams)
                // Only check after generating reasonable amount of tokens
                if (i >= 30) {
                    tokenWindow.add(nextTokenId.toLong())
                    if (tokenWindow.size > 20) tokenWindow.removeAt(0)

                    if (tokenWindow.size >= 5) {
                        // Check 4-grams (require 4+ repetitions)
                        val fourgram = tokenWindow.takeLast(4)
                        ngramCache[fourgram] = (ngramCache[fourgram] ?: 0) + 1
                        if (ngramCache[fourgram]!! >= 4) {
                            Timber.d("4-gram repetition detected: $fourgram (${ngramCache[fourgram]} times)")
                            ngramRepetitions++
                        }

                        // Check 5-grams (require 3+ repetitions)
                        if (tokenWindow.size >= 6) {
                            val fivegram = tokenWindow.takeLast(5)
                            ngramCache[fivegram] = (ngramCache[fivegram] ?: 0) + 1
                            if (ngramCache[fivegram]!! >= 3) {
                                Timber.d("5-gram repetition detected: $fivegram (${ngramCache[fivegram]} times)")
                                ngramRepetitions++
                            }
                        }

                        if (ngramRepetitions >= 3) {
                            Timber.d("Multiple n-gram repetitions detected, stopping")
                            break
                        }
                    }
                }

                // 4. Count sentence endings for monitoring (no early stopping)
                if (tokenText.matches(Regex(".*[.!?]\\s*"))) {
                    sentenceEndCount++
                }

                // Extend attention mask for next iteration
                fullAttentionMask.add(1L)

                // Stop if sequence gets too long
                if (fullAttentionMask.size > 512) {
                    Timber.d("Max sequence length reached, stopping")
                    break
                }
            }

            // Clean up KV cache
            kvCache?.values?.forEach { it.close() }

            val inferenceTime = System.currentTimeMillis() - startTime

            // Final result with full text
            val fullText = tokenizer.decode(generatedTokens.toLongArray(), skipSpecialTokens = true)
            Timber.i("Text generation complete: ${generatedTokens.size} tokens in ${inferenceTime}ms")

            emit(
                InferenceResult.Success(
                    text = fullText,
                    tokensGenerated = generatedTokens.size,
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
