package com.localai.assistant.data.repository

import com.localai.assistant.data.local.ModelDownloader
import com.localai.assistant.data.local.ModelFileManager
import com.localai.assistant.domain.model.ModelStatus
import com.localai.assistant.domain.repository.ModelAvailabilityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelAvailabilityRepositoryImpl @Inject constructor(
    private val fileManager: ModelFileManager,
    private val downloader: ModelDownloader,
) : ModelAvailabilityRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<ModelStatus>(initialStatus())
    override val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var downloadJob: Job? = null

    override fun startDownload() {
        if (downloadJob?.isActive == true) return
        if (fileManager.isModelPresent()) {
            _status.value = ModelStatus.Ready
            return
        }
        downloadJob = scope.launch {
            downloader.download(fileManager.downloadUrl, fileManager.modelFile).collect { progress ->
                _status.value = when (progress) {
                    is ModelDownloader.DownloadProgress.Progress ->
                        ModelStatus.Downloading(progress.bytesDone, progress.bytesTotal)
                    is ModelDownloader.DownloadProgress.Complete -> {
                        fileManager.markComplete()
                        Timber.i("Model download complete: ${fileManager.modelFile.length()} bytes")
                        ModelStatus.Ready
                    }
                    is ModelDownloader.DownloadProgress.Failed ->
                        ModelStatus.Failed(progress.message)
                }
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _status.value = if (fileManager.isModelPresent()) ModelStatus.Ready else ModelStatus.Missing
    }

    override fun modelFilePath(): String? =
        fileManager.modelFile.takeIf { fileManager.isModelPresent() }?.absolutePath

    private fun initialStatus(): ModelStatus =
        if (fileManager.isModelPresent()) ModelStatus.Ready else ModelStatus.Missing
}
