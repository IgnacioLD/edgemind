package com.localai.assistant.data.repository

import com.localai.assistant.data.local.dao.ConversationDao
import com.localai.assistant.data.local.model.ConversationEntity
import com.localai.assistant.data.mapper.ConversationMapper
import com.localai.assistant.data.mapper.ConversationMapper.toEntity
import com.localai.assistant.domain.model.Conversation
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Implementation of ConversationRepository
 * Handles conversation persistence using Room database
 */
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
            .combine(conversationDao.getAllConversations()) { conversations, _ ->
                conversations.map { entity ->
                    // For list view, we don't need all messages
                    ConversationMapper.toDomain(entity, emptyList())
                }
            }
    }

    override fun getConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversation(conversationId)
            .combine(conversationDao.getMessagesForConversation(conversationId)) { entity, messages ->
                entity?.let {
                    ConversationMapper.toDomain(it, messages)
                }
            }
    }

    override suspend fun createConversation(title: String): Result<Conversation> {
        return try {
            val conversation = Conversation(
                id = UUID.randomUUID().toString(),
                title = title,
                messages = emptyList()
            )
            conversationDao.insertConversation(ConversationMapper.toEntity(conversation))
            Result.success(conversation)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create conversation")
            Result.failure(e)
        }
    }

    override suspend fun addMessage(conversationId: String, message: Message): Result<Unit> {
        return try {
            conversationDao.insertMessage(message.toEntity(conversationId))

            // Update conversation timestamp
            conversationDao.getConversation(conversationId)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add message")
            Result.failure(e)
        }
    }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> {
        return try {
            conversationDao.deleteMessagesForConversation(conversationId)
            conversationDao.deleteConversation(
                ConversationEntity(
                    id = conversationId,
                    title = "",
                    createdAt = 0,
                    updatedAt = 0
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete conversation")
            Result.failure(e)
        }
    }

    override suspend fun clearAllConversations(): Result<Unit> {
        return try {
            conversationDao.deleteAllConversations()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear conversations")
            Result.failure(e)
        }
    }
}
