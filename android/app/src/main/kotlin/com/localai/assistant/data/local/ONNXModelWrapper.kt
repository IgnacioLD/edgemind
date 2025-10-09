package com.localai.assistant.data.local

import ai.onnxruntime.*
import android.content.Context
import android.os.Build
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime wrapper with hardware acceleration support
 * Supports: NPU (NNAPI), GPU, DSP, CPU
 *
 * Hardware acceleration priorities:
 * 1. NNAPI (NPU/TPU/DSP) - Best for quantized models
 * 2. GPU - Good for FP16/FP32 models
 * 3. CPU - Fallback for all devices
 *
 * Granite Docling uses 3-model pipeline:
 * 1. Vision Encoder: pixel_values -> image_features [batch, 64, 576]
 * 2. Embed Tokens: input_ids -> inputs_embeds [batch, seq_len, 576]
 * 3. Decoder: combined_embeds -> logits [batch, seq_len, vocab_size]
 */
class ONNXModelWrapper(
    private val context: Context,
    private val modelPath: String
) {

    private var visionEncoderSession: OrtSession? = null
    private var embedTokensSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var accelerationType: AccelerationType = AccelerationType.CPU

    enum class AccelerationType {
        NNAPI,  // NPU/TPU/DSP via Android NNAPI
        GPU,    // GPU via OpenCL/Vulkan
        CPU     // CPU fallback
    }

    /**
     * Initialize ONNX Runtime with hardware detection
     * Loads 3-model Granite Docling pipeline
     */
    fun initialize(): Result<AccelerationType> {
        return try {
            Timber.d("Initializing ONNX Runtime for Granite Docling (3-model pipeline)...")

            // Create ONNX environment
            environment = OrtEnvironment.getEnvironment()

            // Get model directory
            val modelsDir = getModelDirectory()

            // Get paths to 3 models (FP16 versions for compatibility)
            val visionEncoderPath = "$modelsDir/vision_encoder_fp16.onnx"
            val embedTokensPath = "$modelsDir/embed_tokens_fp16.onnx"
            val decoderPath = "$modelsDir/decoder_model_merged_fp16.onnx"

            // Try acceleration backends in priority order for all 3 models
            accelerationType = initializeWithAcceleration(visionEncoderPath, embedTokensPath, decoderPath)

            Timber.i("✅ ONNX Runtime initialized with ${accelerationType.name}")
            Timber.i("   - Vision Encoder: $visionEncoderPath")
            Timber.i("   - Embed Tokens: $embedTokensPath")
            Timber.i("   - Decoder: $decoderPath")
            Result.success(accelerationType)

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize ONNX Runtime")
            Result.failure(e)
        }
    }

    /**
     * Try different acceleration backends using file-based loading (memory-mapped)
     * Loads all 3 Granite Docling models with same acceleration backend
     */
    private fun initializeWithAcceleration(
        visionEncoderPath: String,
        embedTokensPath: String,
        decoderPath: String
    ): AccelerationType {
        val env = environment ?: throw IllegalStateException("Environment not initialized")

        Timber.d("Loading 3-model pipeline...")

        // Priority 1: Try NNAPI (NPU/TPU/DSP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Timber.d("Attempting NNAPI (NPU/TPU/DSP) acceleration...")

                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.addNnapi()  // Enable NNAPI
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                sessionOptions.setIntraOpNumThreads(4)
                sessionOptions.setInterOpNumThreads(4)

                // Load all 3 models with NNAPI
                visionEncoderSession = env.createSession(visionEncoderPath, sessionOptions)
                embedTokensSession = env.createSession(embedTokensPath, sessionOptions)
                decoderSession = env.createSession(decoderPath, sessionOptions)

                Timber.i("🚀 NNAPI acceleration enabled for all 3 models")
                return AccelerationType.NNAPI

            } catch (e: Exception) {
                Timber.w("NNAPI failed, trying CPU: ${e.message}")
                // Clean up any partially loaded sessions
                visionEncoderSession?.close()
                embedTokensSession?.close()
                decoderSession?.close()
                visionEncoderSession = null
                embedTokensSession = null
                decoderSession = null
            }
        } else {
            Timber.d("NNAPI requires Android 9+, current: ${Build.VERSION.SDK_INT}")
        }

        // Priority 2: CPU (always works)
        try {
            Timber.d("Initializing CPU backend with memory-mapped files...")

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            sessionOptions.setIntraOpNumThreads(4)  // Use multiple cores
            sessionOptions.setInterOpNumThreads(4)

            // Load all 3 models with CPU
            visionEncoderSession = env.createSession(visionEncoderPath, sessionOptions)
            Timber.d("  ✓ Vision encoder loaded")

            embedTokensSession = env.createSession(embedTokensPath, sessionOptions)
            Timber.d("  ✓ Embed tokens loaded")

            decoderSession = env.createSession(decoderPath, sessionOptions)
            Timber.d("  ✓ Decoder loaded")

            Timber.i("✅ CPU acceleration enabled for all 3 models")
            return AccelerationType.CPU

        } catch (e: Exception) {
            Timber.e(e, "CPU initialization failed")
            // Clean up
            visionEncoderSession?.close()
            embedTokensSession?.close()
            decoderSession?.close()
            throw e
        }
    }

    /**
     * Run document processing inference with image + text prompt
     * 3-model pipeline:
     * 1. Vision Encoder: [batch, num_images, 3, 512, 512] -> [batch, 64, 576]
     * 2. Embed Tokens: [batch, seq_len] -> [batch, seq_len, 576]
     * 3. Decoder: [batch, 64+seq_len, 576] -> [batch, seq_len, vocab_size]
     */
    fun runDocumentInference(
        pixelValues: FloatArray,  // Pre-processed image [1, 1, 3, 512, 512]
        pixelAttentionMask: BooleanArray,  // Attention mask [1, 1, 512, 512]
        inputIds: LongArray,  // Token IDs [seq_len]
        attentionMask: LongArray  // Text attention mask [seq_len]
    ): FloatArray {
        val visionEncoder = visionEncoderSession ?: throw IllegalStateException("Vision encoder not initialized")
        val embedTokens = embedTokensSession ?: throw IllegalStateException("Embed tokens not initialized")
        val decoder = decoderSession ?: throw IllegalStateException("Decoder not initialized")

        try {
            val env = environment ?: throw IllegalStateException("Environment not initialized")

            Timber.d("Running 3-model pipeline: image [1,1,3,512,512] + text [${inputIds.size}]")

            // Step 1: Vision Encoder - Process image to get image features
            Timber.d("  1/3 Running vision encoder...")
            val pixelValuesTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(pixelValues),
                longArrayOf(1, 1, 3, 512, 512)  // [batch, num_images, channels, height, width]
            )

            val pixelAttentionMaskTensor = OnnxTensor.createTensor(
                env,
                ByteBuffer.wrap(pixelAttentionMask.map { if (it) 1.toByte() else 0.toByte() }.toByteArray()),
                longArrayOf(1, 1, 512, 512)  // [batch, num_images, height, width]
            )

            val visionOutputs = visionEncoder.run(mapOf(
                "pixel_values" to pixelValuesTensor,
                "pixel_attention_mask" to pixelAttentionMaskTensor
            ))

            val imageFeatures = (visionOutputs["image_features"] as? OnnxTensor)
                ?: throw IllegalStateException("Vision encoder output missing")

            val imageFeatureShape = imageFeatures.info.shape
            Timber.d("    Image features shape: [${imageFeatureShape.joinToString(", ")}]")

            // Step 2: Embed Tokens - Convert input_ids to embeddings
            Timber.d("  2/3 Running embed tokens...")
            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, inputIds.size.toLong())  // [batch, seq_len]
            )

            val embedOutputs = embedTokens.run(mapOf(
                "input_ids" to inputIdsTensor
            ))

            val inputsEmbeds = (embedOutputs["inputs_embeds"] as? OnnxTensor)
                ?: throw IllegalStateException("Embed tokens output missing")

            val embedShape = inputsEmbeds.info.shape
            Timber.d("    Text embeddings shape: [${embedShape.joinToString(", ")}]")

            // Step 3: Concatenate image features and text embeddings
            // Image features: [1, 64, 576], Text embeds: [1, seq_len, 576]
            // Combined: [1, 64 + seq_len, 576]
            Timber.d("  3/3 Concatenating embeddings and running decoder...")
            val imageFeatsArray = imageFeatures.floatBuffer.let {
                val arr = FloatArray(it.remaining())
                it.get(arr)
                arr
            }

            val textEmbedsArray = inputsEmbeds.floatBuffer.let {
                val arr = FloatArray(it.remaining())
                it.get(arr)
                arr
            }

            val combinedEmbeds = imageFeatsArray + textEmbedsArray
            val combinedSeqLen = 64 + inputIds.size  // 64 image tokens + text tokens

            val combinedEmbedsTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(combinedEmbeds),
                longArrayOf(1, combinedSeqLen.toLong(), 576)  // [batch, total_seq_len, hidden_size]
            )

            // Create combined attention mask [1, 64 + seq_len]
            // All 1s for image tokens, then the text attention mask
            val combinedAttentionMask = LongArray(combinedSeqLen) { if (it < 64) 1L else attentionMask[it - 64] }
            val combinedAttentionMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(combinedAttentionMask),
                longArrayOf(1, combinedSeqLen.toLong())
            )

            // Create empty past_key_values (no KV caching for first pass)
            val decoderInputs = mutableMapOf<String, OnnxTensor>(
                "inputs_embeds" to combinedEmbedsTensor,
                "attention_mask" to combinedAttentionMaskTensor
            )

            // Add empty past_key_values for 30 layers
            for (i in 0 until 30) {
                val emptyKV = FloatArray(0)
                decoderInputs["past_key_values.$i.key"] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(emptyKV),
                    longArrayOf(1, 3, 0, 64)  // [batch, num_heads, 0, head_dim]
                )
                decoderInputs["past_key_values.$i.value"] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(emptyKV),
                    longArrayOf(1, 3, 0, 64)
                )
            }

            val decoderOutputs = decoder.run(decoderInputs)

            // Get logits output
            val logits = (decoderOutputs["logits"] as? OnnxTensor)
                ?: throw IllegalStateException("Decoder output missing")

            val logitsBuffer = logits.floatBuffer
            val result = FloatArray(logitsBuffer.remaining())
            logitsBuffer.get(result)

            Timber.d("Document inference complete, logits size: ${result.size}")

            // Clean up
            pixelValuesTensor.close()
            pixelAttentionMaskTensor.close()
            visionOutputs.close()
            inputIdsTensor.close()
            embedOutputs.close()
            decoderInputs.values.forEach { it.close() }
            decoderOutputs.close()

            return result

        } catch (e: Exception) {
            Timber.e(e, "Document inference failed")
            throw e
        }
    }

    /**
     * Get hardware info for debugging
     */
    fun getHardwareInfo(): HardwareInfo {
        return HardwareInfo(
            accelerationType = accelerationType,
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.SDK_INT,
            processor = Build.HARDWARE,
            nnApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            chipset = getChipsetInfo()
        )
    }

    /**
     * Detect chipset for NPU capabilities
     */
    private fun getChipsetInfo(): String {
        return when {
            Build.HARDWARE.contains("qcom", ignoreCase = true) -> "Snapdragon (NPU: Hexagon)"
            Build.HARDWARE.contains("mt", ignoreCase = true) -> "MediaTek (NPU: APU)"
            Build.HARDWARE.contains("exynos", ignoreCase = true) -> "Exynos (NPU: Custom)"
            Build.HARDWARE.contains("kirin", ignoreCase = true) -> "Kirin (NPU: Da Vinci)"
            else -> "Unknown (${Build.HARDWARE})"
        }
    }

    /**
     * Get model directory for loading 3 ONNX files
     * Priority: External storage (for large models) -> Assets (not supported for memory-mapping)
     */
    private fun getModelDirectory(): String {
        // Try external storage first (where we'll push the 3 Granite models)
        val externalFilesDir = context.getExternalFilesDir(null)
        Timber.d("External files directory: $externalFilesDir")

        val modelsDir = externalFilesDir?.let {
            java.io.File(it, "models")
        }

        if (modelsDir != null) {
            Timber.d("Checking for models directory at: ${modelsDir.absolutePath}")
            Timber.d("Directory exists: ${modelsDir.exists()}")

            if (modelsDir.exists()) {
                // List files in models directory
                val files = modelsDir.listFiles()
                Timber.d("Files in models directory: ${files?.map { it.name }?.joinToString(", ") ?: "none"}")

                // Check if all 3 required models exist (FP16 versions)
                val visionEncoder = java.io.File(modelsDir, "vision_encoder_fp16.onnx")
                val embedTokens = java.io.File(modelsDir, "embed_tokens_fp16.onnx")
                val decoder = java.io.File(modelsDir, "decoder_model_merged_fp16.onnx")

                if (visionEncoder.exists() && embedTokens.exists() && decoder.exists()) {
                    val totalSize = (visionEncoder.length() + embedTokens.length() + decoder.length()) / (1024 * 1024)
                    Timber.i("Found all 3 Granite Docling models in external storage (~${totalSize}MB total)")
                    return modelsDir.absolutePath
                } else {
                    Timber.w("Missing models: " +
                            "vision_encoder=${visionEncoder.exists()}, " +
                            "embed_tokens=${embedTokens.exists()}, " +
                            "decoder=${decoder.exists()}")
                }
            }
        } else {
            Timber.w("External files directory is null")
        }

        throw IllegalStateException(
            "Model files not found in external storage. Expected 3 models at: " +
                    "${context.getExternalFilesDir(null)}/models/\n" +
                    "- vision_encoder_fp16.onnx\n" +
                    "- embed_tokens_fp16.onnx\n" +
                    "- decoder_model_merged_fp16.onnx"
        )
    }

    /**
     * Check if all 3 models are loaded
     */
    fun isLoaded(): Boolean =
        visionEncoderSession != null && embedTokensSession != null && decoderSession != null

    /**
     * Get acceleration type
     */
    fun getAccelerationType(): AccelerationType = accelerationType

    /**
     * Clean up resources for all 3 models
     */
    fun close() {
        try {
            visionEncoderSession?.close()
            visionEncoderSession = null
            Timber.d("Vision encoder session closed")

            embedTokensSession?.close()
            embedTokensSession = null
            Timber.d("Embed tokens session closed")

            decoderSession?.close()
            decoderSession = null
            Timber.d("Decoder session closed")

            Timber.i("All ONNX sessions closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing sessions")
        }
    }

    data class HardwareInfo(
        val accelerationType: AccelerationType,
        val deviceModel: String,
        val manufacturer: String,
        val androidVersion: Int,
        val processor: String,
        val nnApiAvailable: Boolean,
        val chipset: String
    ) {
        override fun toString(): String {
            return """
                Hardware Info:
                - Device: $manufacturer $deviceModel
                - Android: API $androidVersion
                - Processor: $processor
                - Chipset: $chipset
                - Acceleration: ${accelerationType.name}
                - NNAPI Available: $nnApiAvailable
            """.trimIndent()
        }
    }
}
