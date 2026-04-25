package com.localai.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.assistant.data.local.AndroidTtsEngine
import com.localai.assistant.data.local.AudioRecorder
import com.localai.assistant.data.local.Gemma4ModelWrapper
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.model.ModelStatus
import com.localai.assistant.domain.repository.ModelAvailabilityRepository
import com.localai.assistant.domain.usecase.CreateConversationUseCase
import com.localai.assistant.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val createConversationUseCase: CreateConversationUseCase,
    private val modelAvailability: ModelAvailabilityRepository,
    private val audioRecorder: AudioRecorder,
    private val tts: AndroidTtsEngine,
    private val gemma: Gemma4ModelWrapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var recordingJob: Job? = null

    init {
        observeModelStatus()
        initializeConversation()
    }

    private fun observeModelStatus() {
        viewModelScope.launch {
            modelAvailability.status.collect { status ->
                _uiState.update { it.copy(modelStatus = status) }
                if (status is ModelStatus.Ready && !gemma.isLoaded()) {
                    preloadModel()
                }
            }
        }
    }

    private fun preloadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparing = true) }
            try {
                gemma.preload()
            } catch (e: Exception) {
                Timber.e(e, "Model preload failed")
                _uiState.update { it.copy(error = "Failed to load model: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isPreparing = false) }
            }
        }
    }

    private fun initializeConversation() {
        viewModelScope.launch {
            createConversationUseCase()
                .onSuccess { conversation ->
                    _uiState.update { it.copy(conversationId = conversation.id) }
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to initialize conversation")
                    _uiState.update {
                        it.copy(error = "Failed to start conversation: ${error.message}")
                    }
                }
        }
    }

    fun downloadModel() {
        modelAvailability.startDownload()
    }

    fun cancelDownload() {
        modelAvailability.cancelDownload()
    }

    fun hasMicPermission(): Boolean = audioRecorder.hasMicPermission()

    fun startRecording() {
        if (recordingJob?.isActive == true) return
        if (_uiState.value.modelStatus !is ModelStatus.Ready) return
        if (!audioRecorder.hasMicPermission()) return

        _uiState.update { it.copy(isRecording = true, error = null) }
        recordingJob = viewModelScope.launch {
            try {
                val pcm = audioRecorder.recordUntilStop { !_uiState.value.isRecording }
                _uiState.update { it.copy(isRecording = false) }
                if (pcm.isNotEmpty()) {
                    sendVoice(pcm)
                }
            } catch (e: Exception) {
                Timber.e(e, "Recording failed")
                _uiState.update {
                    it.copy(isRecording = false, error = "Recording failed: ${e.message}")
                }
            }
        }
    }

    fun stopRecording() {
        if (_uiState.value.isRecording) {
            audioRecorder.stop()
            _uiState.update { it.copy(isRecording = false) }
        }
    }

    fun cancelRecording() {
        audioRecorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        _uiState.update { it.copy(isRecording = false) }
    }

    private fun sendVoice(audioPcm: ByteArray) {
        val conversationId = _uiState.value.conversationId ?: return
        // The user's message text comes back as part of Gemma's transcription/answer; for the bubble,
        // show a placeholder until the assistant reply is added.
        val userMessage = Message(
            content = "🎙️ (voice message)",
            role = MessageRole.USER,
        )
        dispatchInference(conversationId, userMessage, audioPcm)
    }

    fun sendMessage(content: String, imageUri: String? = null) {
        if (content.isBlank()) return
        if (_uiState.value.modelStatus !is ModelStatus.Ready) return

        val conversationId = _uiState.value.conversationId ?: return
        val userMessage = Message(
            content = content,
            role = MessageRole.USER,
            imageUri = imageUri,
        )
        dispatchInference(conversationId, userMessage, audioPcm = null)
    }

    private fun dispatchInference(
        conversationId: String,
        userMessage: Message,
        audioPcm: ByteArray?,
    ) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true,
                error = null,
            )
        }

        generationJob = viewModelScope.launch {
            sendMessageUseCase(conversationId, userMessage, audioPcm)
                .catch { error ->
                    Timber.e(error, "Error sending message")
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to send message: ${error.message}")
                    }
                }
                .collect { result ->
                    when (result) {
                        is InferenceResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                        is InferenceResult.Streaming -> {
                            _uiState.update { state ->
                                val messages = state.messages.toMutableList()
                                if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                                    val lastMsg = messages.removeLast()
                                    messages.add(lastMsg.copy(content = lastMsg.content + result.text))
                                } else {
                                    messages.add(Message(content = result.text, role = MessageRole.ASSISTANT))
                                }
                                state.copy(messages = messages, isLoading = true)
                            }
                        }
                        is InferenceResult.Success -> {
                            _uiState.update { state ->
                                val messages = state.messages.toMutableList()
                                if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                                    messages.removeLast()
                                }
                                messages.add(Message(content = result.text, role = MessageRole.ASSISTANT))
                                state.copy(messages = messages, isLoading = false, error = null)
                            }
                            tts.speak(result.text)
                            Timber.d("Generation complete: ${result.tokensGenerated} chunks in ${result.inferenceTimeMs}ms")
                        }
                        is InferenceResult.Error -> {
                            _uiState.update {
                                it.copy(isLoading = false, error = result.message)
                            }
                            Timber.e("Inference error: ${result.message}")
                        }
                    }
                }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        tts.stop()
        _uiState.update { it.copy(isLoading = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
    }
}

data class ChatUiState(
    val conversationId: String? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isRecording: Boolean = false,
    val isPreparing: Boolean = false,
    val error: String? = null,
    val modelStatus: ModelStatus = ModelStatus.Missing,
)
