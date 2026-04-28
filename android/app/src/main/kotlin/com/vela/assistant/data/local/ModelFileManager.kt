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
        // The Qualcomm-optimized .litertlm in IgnacioLD/gemma-4-E2B-it-litert-lm fails to load
        // on Snapdragon 8 Gen 1 (S22) — both Backend.GPU() and Backend.CPU() init throw, which
        // means it's compiled against a backend (almost certainly QNN / Hexagon NPU) that
        // LiteRT-LM 0.10.2 doesn't expose. Re-evaluate when LiteRT-LM ships a QNN backend.
        const val HF_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MODELS_SUBDIR = "models"
    }
}
