package com.vela.assistant.domain.model

sealed class ModelStatus {
    data object Missing : ModelStatus()
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : ModelStatus()
    data object Ready : ModelStatus()
    data class Failed(val message: String) : ModelStatus()
}
