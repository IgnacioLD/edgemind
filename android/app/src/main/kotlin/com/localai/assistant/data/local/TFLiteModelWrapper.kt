package com.localai.assistant.data.local

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wrapper for TensorFlow Lite model inference
 * Handles model loading, GPU delegation, and inference
 */
class TFLiteModelWrapper(
    private val context: Context,
    private val modelPath: String
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    /**
     * Initialize the model with GPU delegate if available
     */
    fun initialize(): Result<Unit> {
        return try {
            val model = loadModelFile()
            val options = Interpreter.Options().apply {
                // Try to use GPU delegate for faster inference
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                    Timber.d("Using GPU delegate for inference")
                } else {
                    // Use NNAPI delegate as fallback
                    useNNAPI = true
                    Timber.d("Using NNAPI for inference")
                }

                // Set number of threads for CPU fallback
                numThreads = 4
            }

            interpreter = Interpreter(model, options)
            Timber.i("Model loaded successfully: $modelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model: $modelPath")
            Result.failure(e)
        }
    }

    /**
     * Run inference on the model
     * Note: This is a placeholder - actual implementation depends on model input/output format
     */
    fun runInference(input: FloatArray): Result<FloatArray> {
        return try {
            val interpreter = this.interpreter
                ?: return Result.failure(IllegalStateException("Model not initialized"))

            val output = FloatArray(1024) // Adjust based on actual model output
            interpreter.run(input, output)

            Result.success(output)
        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
            Result.failure(e)
        }
    }

    /**
     * Clean up resources
     */
    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
        Timber.d("Model closed: $modelPath")
    }

    /**
     * Load model file from assets
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
