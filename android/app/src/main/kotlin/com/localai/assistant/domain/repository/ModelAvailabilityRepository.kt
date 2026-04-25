package com.localai.assistant.domain.repository

import com.localai.assistant.domain.model.ModelStatus
import kotlinx.coroutines.flow.StateFlow

interface ModelAvailabilityRepository {
    val status: StateFlow<ModelStatus>

    fun startDownload()
    fun cancelDownload()

    fun modelFilePath(): String?
}
