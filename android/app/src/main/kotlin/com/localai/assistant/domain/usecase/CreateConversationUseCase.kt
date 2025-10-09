package com.localai.assistant.domain.usecase

import com.localai.assistant.domain.model.Conversation
import com.localai.assistant.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Use case for creating a new conversation
 */
class CreateConversationUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    suspend operator fun invoke(title: String = "New Conversation"): Result<Conversation> {
        return repository.createConversation(title)
    }
}
