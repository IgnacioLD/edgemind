// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.assistant.data.local.AndroidTtsEngine
import com.vela.assistant.data.local.AudioRecorder
import com.vela.assistant.data.local.Gemma4ModelWrapper
import com.vela.assistant.domain.model.InferenceResult
import com.vela.assistant.domain.model.Message
import com.vela.assistant.domain.model.MessageRole
import com.vela.assistant.domain.model.ModelStatus
import com.vela.assistant.domain.repository.ModelAvailabilityRepository
import com.vela.assistant.domain.usecase.CreateConversationUseCase
import com.vela.assistant.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // One-shot UI notices (shown via Snackbar). Distinct from `error` in uiState because notices
    // are transient and shouldn't persist as a banner — they fire, surface, dismiss themselves.
    private val _systemNotices = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val systemNotices: SharedFlow<String> = _systemNotices.asSharedFlow()

    private var generationJob: Job? = null
    private var recordingJob: Job? = null

    init {
        observeModelStatus()
        observeAutoReset()
        initializeConversation()
    }

    private fun observeAutoReset() {
        viewModelScope.launch {
            gemma.autoResetEvents.collect {
                _systemNotices.tryEmit("Conversation reset to keep responses fresh.")
            }
        }
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
                val pcm = audioRecorder.recordUntilStop(
                    stopSignal = { !_uiState.value.isRecording },
                )
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
        // Build a duration label + downsampled waveform envelope from the raw PCM so the user's
        // bubble can render like a proper voice note (mic + bars + 0:03) instead of a placeholder.
        // Audio format is 16 kHz mono int16 little-endian: 2 bytes per sample, 32_000 bytes/second.
        val sampleCount = audioPcm.size / 2
        val durationMs = (sampleCount.toLong() * 1000L) / 16_000L
        val waveform = downsampleAmplitudes(audioPcm, buckets = 28)
        val userMessage = Message(
            content = "🎙️ (voice message)",
            role = MessageRole.USER,
            voiceDurationMs = durationMs,
            voiceWaveform = waveform,
        )
        // Immediate auditory ack so the user hears something *before* the long inference +
        // tool-execute + decode cycle. Without this, the user releases the mic and gets several
        // seconds of silence before the final TTS, which feels broken. The ack is fire-and-
        // forget; the final response will flush this when it speaks.
        tts.speak("OK")
        dispatchInference(conversationId, userMessage, audioPcm)
    }

    // Peak-of-abs over each bucket, normalized to the loudest bucket. Peak (vs RMS) gives a
    // more "voice-note"-y crisp envelope; normalization keeps the bars filling the bubble height
    // even for quiet recordings.
    private fun downsampleAmplitudes(pcm: ByteArray, buckets: Int): List<Float> {
        val sampleCount = pcm.size / 2
        if (sampleCount <= 0 || buckets <= 0) return emptyList()
        val perBucket = (sampleCount + buckets - 1) / buckets
        val out = FloatArray(buckets)
        var max = 0f
        for (b in 0 until buckets) {
            val start = b * perBucket
            val end = minOf(start + perBucket, sampleCount)
            var peak = 0
            for (i in start until end) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val abs = if (sample < 0) -sample else sample
                if (abs > peak) peak = abs
            }
            val v = peak.toFloat()
            out[b] = v
            if (v > max) max = v
        }
        if (max <= 0f) return out.toList()
        for (i in out.indices) out[i] = (out[i] / max).coerceIn(0f, 1f)
        return out.toList()
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

    // Wipes the model's KV cache and the on-screen messages, then re-warms the conversation in
    // the background so the next user turn doesn't pay a 140 s cold-prefill. Use this when the
    // model starts collapsing (1-token responses, off-topic / wrong-language gibberish) — that's
    // a sign the persistent conversation has accumulated poisoned context from prior turns.
    fun newConversation() {
        viewModelScope.launch {
            generationJob?.cancel()
            generationJob = null
            tts.stop()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    isLoading = false,
                    error = null,
                    isPreparing = true,
                )
            }
            try {
                gemma.resetConversation()
                gemma.preload()
            } catch (e: Exception) {
                Timber.e(e, "Reset failed")
                _uiState.update { it.copy(error = "Failed to reset: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isPreparing = false) }
            }
        }
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
