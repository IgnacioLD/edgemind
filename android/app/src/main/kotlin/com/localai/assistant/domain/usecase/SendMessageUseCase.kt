package com.localai.assistant.domain.usecase

import com.localai.assistant.domain.model.InferenceRequest
import com.localai.assistant.domain.model.InferenceResult
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.model.ModelType
import com.localai.assistant.domain.repository.ConversationRepository
import com.localai.assistant.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Use case for sending a message and getting AI response
 * Follows Single Responsibility Principle
 */
class SendMessageUseCase @Inject constructor(
    private val modelRepository: ModelRepository,
    private val conversationRepository: ConversationRepository,
    private val intentRouter: IntentRouterUseCase
) {
    /**
     * Execute the use case
     * @param conversationId The conversation to add the message to
     * @param userMessage The message from the user
     * @return Flow of inference results
     */
    operator fun invoke(
        conversationId: String,
        userMessage: Message
    ): Flow<InferenceResult> {
        return modelRepository.runInference(
            request = InferenceRequest(
                prompt = userMessage.content,
                modelType = intentRouter.routeIntent(userMessage),
                imageUri = userMessage.imageUri  // Use imageUri field for document images
            )
        ).onEach { result ->
            // Save user message
            conversationRepository.addMessage(conversationId, userMessage)

            // Save assistant response when complete
            if (result is InferenceResult.Success) {
                conversationRepository.addMessage(
                    conversationId = conversationId,
                    message = Message(
                        content = result.text,
                        role = MessageRole.ASSISTANT
                    )
                )
            }
        }.catch { exception ->
            emit(
                InferenceResult.Error(
                    message = "Failed to process message: ${exception.message}",
                    cause = exception
                )
            )
        }
    }
}
