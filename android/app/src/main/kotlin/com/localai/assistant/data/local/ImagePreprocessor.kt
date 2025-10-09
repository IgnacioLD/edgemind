package com.localai.assistant.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import timber.log.Timber

/**
 * Image preprocessing for Granite Docling document processing
 * Converts Android Bitmap to ONNX-compatible format:
 * - Resize to 512x512
 * - Convert RGB
 * - Normalize to [0, 1]
 * - Transpose to [C, H, W] format
 * - Generate pixel attention mask
 */
class ImagePreprocessor(private val context: Context) {

    companion object {
        private const val INPUT_WIDTH = 512
        private const val INPUT_HEIGHT = 512
    }

    /**
     * Result of image preprocessing containing pixel values and attention mask
     */
    data class PreprocessedImage(
        val pixelValues: FloatArray,  // [1, 1, 3, 512, 512]
        val pixelAttentionMask: BooleanArray  // [1, 1, 512, 512]
    )

    /**
     * Preprocess document image for Granite Docling
     * Returns pixel values [1, 1, 3, 512, 512] and attention mask [1, 1, 512, 512]
     */
    fun preprocessImage(bitmap: Bitmap): PreprocessedImage {
        Timber.d("Preprocessing image: ${bitmap.width}x${bitmap.height}")

        // Step 1: Resize to 512x512
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            INPUT_WIDTH,
            INPUT_HEIGHT,
            true  // Use bilinear filtering for better quality
        )

        Timber.d("Resized to 512x512")

        // Step 2: Extract pixels and convert to float array
        // Android bitmap format: ARGB_8888 (4 bytes per pixel)
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        resized.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        // Step 3: Convert to [1, 1, 3, 512, 512] format (batch, num_images, CHW layout)
        // Total size: 1 * 1 * 3 * 512 * 512 = 786,432 floats
        val pixelValues = FloatArray(3 * INPUT_HEIGHT * INPUT_WIDTH)

        var idx = 0
        for (c in 0 until 3) {  // Channels: R, G, B
            for (y in 0 until INPUT_HEIGHT) {
                for (x in 0 until INPUT_WIDTH) {
                    val pixel = pixels[y * INPUT_WIDTH + x]

                    // Extract RGB values (0-255) and normalize to [0, 1]
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f  // Red
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f   // Green
                        2 -> (pixel and 0xFF) / 255.0f           // Blue
                        else -> 0f
                    }

                    pixelValues[idx++] = value
                }
            }
        }

        // Step 4: Create pixel attention mask [1, 1, 512, 512]
        // All true since we have a valid image filling the entire 512x512 space
        val pixelAttentionMask = BooleanArray(INPUT_HEIGHT * INPUT_WIDTH) { true }

        Timber.d("Image preprocessed: ${pixelValues.size} floats [1, 1, 3, 512, 512]")

        // Clean up resized bitmap if different from input
        if (resized != bitmap) {
            resized.recycle()
        }

        return PreprocessedImage(pixelValues, pixelAttentionMask)
    }

    /**
     * Load and preprocess image from file path
     */
    fun preprocessImageFromPath(imagePath: String): PreprocessedImage {
        val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
            ?: throw IllegalArgumentException("Failed to load image from $imagePath")

        try {
            return preprocessImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Preprocess image from byte array (e.g., camera capture)
     */
    fun preprocessImageFromBytes(imageBytes: ByteArray): PreprocessedImage {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Failed to decode image bytes")

        try {
            return preprocessImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Preprocess image from content URI (Android file picker)
     */
    fun preprocessImageFromUri(uriString: String): PreprocessedImage {
        val uri = Uri.parse(uriString)
        val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            android.graphics.BitmapFactory.decodeStream(inputStream)
        } ?: throw IllegalArgumentException("Failed to load image from URI: $uriString")

        try {
            return preprocessImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
