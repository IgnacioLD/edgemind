package com.localai.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.usecase.CreateConversationUseCase
import com.localai.assistant.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for chat screen
 * Follows MVVM pattern with Clean Architecture
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val createConversationUseCase: CreateConversationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        initializeConversation()
    }

    private fun initializeConversation() {
        viewModelScope.launch {
            createConversationUseCase()
                .onSuccess { conversation ->
                    _uiState.update { it.copy(conversationId = conversation.id) }
                    Timber.d("Conversation initialized: ${conversation.id}")
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to initialize conversation")
                    _uiState.update {
                        it.copy(error = "Failed to start conversation: ${error.message}")
                    }
                }
        }
    }

    fun sendMessage(content: String, imageUri: String? = null) {
        if (content.isBlank()) return

        val conversationId = _uiState.value.conversationId ?: return

        val userMessage = Message(
            content = content,
            role = MessageRole.USER,
            imageUri = imageUri
        )

        // Add user message to UI immediately
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            sendMessageUseCase(conversationId, userMessage)
                .catch { error ->
                    Timber.e(error, "Error sending message")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to send message: ${error.message}"
                        )
                    }
                }
                .collect { result ->
                    when (result) {
                        is InferenceResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }

                        is InferenceResult.Success -> {
                            val assistantMessage = Message(
                                content = result.text,
                                role = MessageRole.ASSISTANT
                            )
                            _uiState.update { state ->
                                state.copy(
                                    messages = state.messages + assistantMessage,
                                    isLoading = false,
                                    error = null
                                )
                            }
                            Timber.d("Document processed in ${result.inferenceTimeMs}ms")
                        }

                        is InferenceResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = result.message
                                )
                            }
                            Timber.e("Inference error: ${result.message}")
                        }
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

/**
 * UI state for chat screen
 * Immutable data class following best practices
 */
data class ChatUiState(
    val conversationId: String? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
