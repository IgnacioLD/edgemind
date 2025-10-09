package com.localai.assistant.data.local

import ai.onnxruntime.*
import android.content.Context
import android.os.Build
import timber.log.Timber
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
 * Phi-3 mini (3.8B parameters, INT4 quantized to 2.6GB):
 * - Input: input_ids [batch, seq_len], attention_mask [batch, seq_len], past_key_values (32 layers)
 * - Output: logits [batch, seq_len, 32064], present key/values (32 layers)
 * - 32 transformer layers with KV caching support
 */
class ONNXModelWrapper(
    private val context: Context,
    private val modelPath: String
) {

    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var accelerationType: AccelerationType = AccelerationType.CPU

    enum class AccelerationType {
        NNAPI,  // NPU/TPU/DSP via Android NNAPI
        GPU,    // GPU via OpenCL/Vulkan
        CPU     // CPU fallback
    }

    /**
     * Initialize ONNX Runtime with hardware detection
     * Loads Phi-3 mini INT4 model
     */
    fun initialize(): Result<AccelerationType> {
        return try {
            Timber.d("Initializing ONNX Runtime for Phi-3 mini (INT4)...")

            // Create ONNX environment
            environment = OrtEnvironment.getEnvironment()

            // Get model path
            val modelFile = getModelFile()

            // Try acceleration backends in priority order
            accelerationType = initializeWithAcceleration(modelFile)

            Timber.i("✅ ONNX Runtime initialized with ${accelerationType.name}")
            Timber.i("   - Model: $modelFile")
            Result.success(accelerationType)

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize ONNX Runtime")
            Result.failure(e)
        }
    }

    /**
     * Try different acceleration backends using file-based loading (memory-mapped)
     * Priority: NNAPI (NPU) -> CPU
     */
    private fun initializeWithAcceleration(modelPath: String): AccelerationType {
        val env = environment ?: throw IllegalStateException("Environment not initialized")

        Timber.d("Loading Phi-3 model from: $modelPath")

        // Priority 1: Try NNAPI (NPU/TPU/DSP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Timber.d("Attempting NNAPI (NPU/TPU/DSP) acceleration...")

                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.addNnapi()  // Enable NNAPI
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                sessionOptions.setIntraOpNumThreads(4)
                sessionOptions.setInterOpNumThreads(4)

                session = env.createSession(modelPath, sessionOptions)

                Timber.i("🚀 NNAPI acceleration enabled")
                return AccelerationType.NNAPI

            } catch (e: Exception) {
                Timber.w("NNAPI failed, trying CPU: ${e.message}")
                session?.close()
                session = null
            }
        } else {
            Timber.d("NNAPI requires Android 9+, current: ${Build.VERSION.SDK_INT}")
        }

        // Priority 2: CPU (always works)
        try {
            Timber.d("Initializing CPU backend with memory-mapped file...")

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            sessionOptions.setIntraOpNumThreads(4)  // Use multiple cores
            sessionOptions.setInterOpNumThreads(4)

            session = env.createSession(modelPath, sessionOptions)
            Timber.d("  ✓ Phi-3 model loaded")

            Timber.i("✅ CPU acceleration enabled")
            return AccelerationType.CPU

        } catch (e: Exception) {
            Timber.e(e, "CPU initialization failed")
            session?.close()
            throw e
        }
    }

    /**
     * Result from inference with KV cache
     */
    data class InferenceWithCacheResult(
        val logits: FloatArray,
        val presentKeyValues: Map<String, OnnxTensor>  // Cache for next iteration
    )

    /**
     * Run text inference WITH KV caching (fast autoregressive generation)
     * Input: token IDs, attention mask, optional past KV cache
     * Output: logits + present KV cache for next iteration
     */
    fun runInferenceWithCache(
        inputIds: LongArray,  // Token IDs [seq_len]
        attentionMask: LongArray,  // Attention mask [total_seq_len]
        pastKeyValues: Map<String, OnnxTensor>? = null  // Cache from previous step
    ): InferenceWithCacheResult {
        val ortSession = session ?: throw IllegalStateException("Model not initialized")
        val env = environment ?: throw IllegalStateException("Environment not initialized")

        try {
            Timber.d("Running inference with cache: input_ids=${inputIds.size}, has_cache=${pastKeyValues != null}")

            // Create input tensors
            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, inputIds.size.toLong())  // [batch, seq_len]
            )

            val attentionMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(attentionMask),
                longArrayOf(1, attentionMask.size.toLong())  // [batch, total_seq_len]
            )

            // Create inputs map
            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            // Add past_key_values (either from cache or empty for first iteration)
            if (pastKeyValues != null) {
                // Reuse cached KV from previous iteration
                inputs.putAll(pastKeyValues)
            } else {
                // First iteration: empty KV cache
                for (i in 0 until 32) {
                    val emptyKV = FloatArray(0)
                    inputs["past_key_values.$i.key"] = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(emptyKV),
                        longArrayOf(1, 32, 0, 96)
                    )
                    inputs["past_key_values.$i.value"] = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(emptyKV),
                        longArrayOf(1, 32, 0, 96)
                    )
                }
            }

            // Run inference
            val outputs = ortSession.run(inputs)

            // Get logits output
            val logitsValue = outputs.get("logits").orElse(null)
            val logits = (logitsValue as? OnnxTensor)
                ?: throw IllegalStateException("Logits output missing")

            val logitsBuffer = logits.floatBuffer
            val result = FloatArray(logitsBuffer.remaining())
            logitsBuffer.get(result)

            // Extract present_key_values for next iteration
            val presentKeyValues = mutableMapOf<String, OnnxTensor>()
            for (i in 0 until 32) {
                val presentKey = outputs.get("present.$i.key").orElse(null) as? OnnxTensor
                val presentValue = outputs.get("present.$i.value").orElse(null) as? OnnxTensor

                if (presentKey != null && presentValue != null) {
                    // Rename present.X to past_key_values.X for next iteration
                    presentKeyValues["past_key_values.$i.key"] = presentKey
                    presentKeyValues["past_key_values.$i.value"] = presentValue
                }
            }

            Timber.d("Inference complete: logits=${result.size}, cached_layers=${presentKeyValues.size/2}")

            // Clean up input tensors only (keep present for caching)
            inputIdsTensor.close()
            attentionMaskTensor.close()
            if (pastKeyValues == null) {
                // Close the empty KV tensors we created
                for (i in 0 until 32) {
                    inputs["past_key_values.$i.key"]?.close()
                    inputs["past_key_values.$i.value"]?.close()
                }
            }

            return InferenceWithCacheResult(result, presentKeyValues)

        } catch (e: Exception) {
            Timber.e(e, "Inference with cache failed")
            throw e
        }
    }

    /**
     * Run text inference without KV caching (simple greedy decoding)
     * Input: token IDs, attention mask
     * Output: logits for next token prediction
     */
    fun runInference(
        inputIds: LongArray,  // Token IDs [seq_len]
        attentionMask: LongArray  // Attention mask [seq_len]
    ): FloatArray {
        val ortSession = session ?: throw IllegalStateException("Model not initialized")
        val env = environment ?: throw IllegalStateException("Environment not initialized")

        try {
            Timber.d("Running inference with input_ids length: ${inputIds.size}")

            // Create input tensors
            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, inputIds.size.toLong())  // [batch, seq_len]
            )

            val attentionMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(attentionMask),
                longArrayOf(1, attentionMask.size.toLong())  // [batch, seq_len]
            )

            // Create inputs map
            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            // Add empty past_key_values for 32 Phi-3 layers
            // Shape: [batch, num_heads=32, past_seq_len=0, head_dim=96]
            for (i in 0 until 32) {
                val emptyKV = FloatArray(0)
                inputs["past_key_values.$i.key"] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(emptyKV),
                    longArrayOf(1, 32, 0, 96)
                )
                inputs["past_key_values.$i.value"] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(emptyKV),
                    longArrayOf(1, 32, 0, 96)
                )
            }

            // Run inference
            val outputs = ortSession.run(inputs)

            // Debug: Log all output names
            Timber.d("Model outputs: ${outputs.map { it.key }.joinToString(", ")}")

            // Get logits output - OrtSession.Result.get() returns Optional<OnnxValue>
            val logitsValue = outputs.get("logits").orElse(null)
            Timber.d("Logits value type: ${logitsValue?.javaClass?.simpleName}")

            val logits = (logitsValue as? OnnxTensor)
                ?: throw IllegalStateException("Logits output missing. Available outputs: ${outputs.map { it.key }}")

            val logitsBuffer = logits.floatBuffer
            val result = FloatArray(logitsBuffer.remaining())
            logitsBuffer.get(result)

            Timber.d("Inference complete, logits size: ${result.size}")

            // Clean up
            inputs.values.forEach { it.close() }
            outputs.close()

            return result

        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
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
     * Get model file path
     * Priority: External storage (for large models) -> Assets
     */
    private fun getModelFile(): String {
        // Try external storage first (where we'll push Phi-3 model)
        val externalFilesDir = context.getExternalFilesDir(null)
        Timber.d("External files directory: $externalFilesDir")

        val modelsDir = externalFilesDir?.let {
            java.io.File(it, "models")
        }

        if (modelsDir != null && modelsDir.exists()) {
            // List files in models directory
            val files = modelsDir.listFiles()
            Timber.d("Files in models directory: ${files?.map { it.name }?.joinToString(", ") ?: "none"}")

            // Look for Phi-3 model file
            val phi3Model = java.io.File(modelsDir, "phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx")

            if (phi3Model.exists()) {
                val sizeMB = phi3Model.length() / (1024 * 1024)
                Timber.i("Found Phi-3 model in external storage (~${sizeMB}MB)")
                return phi3Model.absolutePath
            } else {
                Timber.w("Phi-3 model not found: ${phi3Model.name}")
            }
        } else {
            Timber.w("Models directory not found or doesn't exist")
        }

        throw IllegalStateException(
            "Phi-3 model file not found in external storage. Expected at: " +
                    "${context.getExternalFilesDir(null)}/models/phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx"
        )
    }

    /**
     * Check if model is loaded
     */
    fun isLoaded(): Boolean = session != null

    /**
     * Get acceleration type
     */
    fun getAccelerationType(): AccelerationType = accelerationType

    /**
     * Clean up resources
     */
    fun close() {
        try {
            session?.close()
            session = null
            Timber.i("ONNX session closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing session")
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
