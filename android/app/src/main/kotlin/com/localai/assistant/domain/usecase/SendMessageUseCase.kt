package com.localai.assistant.domain.usecase

import com.localai.assistant.domain.model.InferenceRequest
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.repository.ConversationRepository
import com.localai.assistant.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val modelRepository: ModelRepository,
    private val conversationRepository: ConversationRepository,
) {
    operator fun invoke(
        conversationId: String,
        userMessage: Message,
        audioPcm: ByteArray? = null,
    ): Flow<InferenceResult> {
        // When audio is present, the audio IS the prompt — don't also forward the placeholder text
        // we put in the user bubble (e.g. "🎙️ (voice message)") or the model will try to answer it.
        val promptText = if (audioPcm != null) "" else userMessage.content
        return modelRepository.runInference(
            request = InferenceRequest(
                prompt = promptText,
                imageUri = userMessage.imageUri,
                audioPcm = audioPcm,
            ),
        )
            // Persist the user's message exactly once at flow start, not per-emission. The previous
            // implementation called addMessage on every Streaming chunk, queueing dozens of redundant
            // Room writes per turn.
            .onStart {
                conversationRepository.addMessage(conversationId, userMessage)
            }
            .onEach { result ->
                if (result is InferenceResult.Success) {
                    conversationRepository.addMessage(
                        conversationId = conversationId,
                        message = Message(
                            content = result.text,
                            role = MessageRole.ASSISTANT,
                        ),
                    )
                }
            }
            .catch { exception ->
                emit(
                    InferenceResult.Error(
                        message = "Failed to process message: ${exception.message}",
                        cause = exception,
                    ),
                )
            }
            // Keep persistence work off Main even when the ChatViewModel collects on Main.
            .flowOn(Dispatchers.IO)
    }
}
