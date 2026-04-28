package com.vela.assistant.domain.repository

import com.vela.assistant.domain.model.ModelStatus
import kotlinx.coroutines.flow.StateFlow

interface ModelAvailabilityRepository {
    val status: StateFlow<ModelStatus>

    fun startDownload()
    fun cancelDownload()

    fun modelFilePath(): String?
}
