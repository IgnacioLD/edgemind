package com.vela.assistant.domain.usecase

import com.vela.assistant.domain.model.Conversation
import com.vela.assistant.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for retrieving all conversations
 */
class GetConversationsUseCase @Inject constructor(
    private val repository: ConversationRepository
) {
    operator fun invoke(): Flow<List<Conversation>> {
        return repository.getAllConversations()
    }
}
