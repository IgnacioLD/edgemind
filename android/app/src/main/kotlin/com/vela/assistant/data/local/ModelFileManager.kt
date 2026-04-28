package com.vela.assistant.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Resolves where the Gemma 4 .litertlm file lives on disk.
// Completion is tracked via a `.ok` sentinel rather than a size comparison: the HF resolve URL
// redirects through a CDN whose Content-Length we don't know in advance, and a hardcoded size
// would make us mis-classify a complete file as partial and try to resume past EOF (HTTP 416).
@Singleton
class ModelFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modelFile: File
        get() = File(context.getExternalFilesDir(MODELS_SUBDIR), MODEL_FILENAME)

    val completionMarker: File
        get() = File(modelFile.parentFile, "${modelFile.name}.ok")

    val downloadUrl: String = "https://huggingface.co/${HF_REPO}/resolve/main/$MODEL_FILENAME"

    fun isModelPresent(): Boolean = modelFile.exists() && completionMarker.exists()

    fun isPartialDownloadPresent(): Boolean = modelFile.exists() && !completionMarker.exists()

    fun markComplete() {
        try {
            completionMarker.parentFile?.mkdirs()
            completionMarker.writeText("${System.currentTimeMillis()}\n${modelFile.length()}")
        } catch (e: Exception) {
            // Sentinel write failure is non-fatal; next launch will reattempt the download but
            // benefit from byte-range resume.
        }
    }

    fun clearAll() {
        modelFile.delete()
        completionMarker.delete()
    }

    private companion object {
        // Pointing at the Qualcomm-optimized build in the project owner's fork while we test
        // whether the new Backend.NPU() cascade (added in Gemma4ModelWrapper) can load it on
        // Snapdragon 8 Gen 1. Revert to "litert-community/gemma-4-E2B-it-litert-lm" +
        // "gemma-4-E2B-it.litertlm" if the NPU backend isn't exposed by LiteRT-LM 0.10.2 or
        // can't satisfy the model's NPU runtime requirements.
        const val HF_REPO = "IgnacioLD/gemma-4-E2B-it-litert-lm"
        const val MODEL_FILENAME = "gemma-4-E2B-it_qualcomm_qcs8275.litertlm"
        const val MODELS_SUBDIR = "models"
    }
}
