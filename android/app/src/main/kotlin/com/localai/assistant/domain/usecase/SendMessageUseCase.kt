package com.localai.assistant.domain.usecase

import com.localai.assistant.domain.model.InferenceRequest
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.repository.ConversationRepository
import com.localai.assistant.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
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
        ).onEach { result ->
            conversationRepository.addMessage(conversationId, userMessage)

            if (result is InferenceResult.Success) {
                conversationRepository.addMessage(
                    conversationId = conversationId,
                    message = Message(
                        content = result.text,
                        role = MessageRole.ASSISTANT,
                    ),
                )
            }
        }.catch { exception ->
            emit(
                InferenceResult.Error(
                    message = "Failed to process message: ${exception.message}",
                    cause = exception,
                ),
            )
        }
    }
}
