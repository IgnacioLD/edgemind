package com.localai.assistant.domain.repository

import com.localai.assistant.domain.model.Conversation
import com.localai.assistant.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for conversation and message persistence
 */
interface ConversationRepository {
    /**
     * Get all conversations
     */
    fun getAllConversations(): Flow<List<Conversation>>

    /**
     * Get a specific conversation by ID
     */
    fun getConversation(conversationId: String): Flow<Conversation?>

    /**
     * Create a new conversation
     */
    suspend fun createConversation(title: String): Result<Conversation>

    /**
     * Add a message to a conversation
     */
    suspend fun addMessage(conversationId: String, message: Message): Result<Unit>

    /**
     * Delete a conversation
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit>

    /**
     * Clear all conversations
     */
    suspend fun clearAllConversations(): Result<Unit>
}
