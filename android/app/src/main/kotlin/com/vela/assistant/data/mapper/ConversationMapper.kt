package com.vela.assistant.data.mapper

import com.vela.assistant.data.local.model.ConversationEntity
import com.vela.assistant.data.local.model.MessageEntity
import com.vela.assistant.domain.model.Conversation
import com.vela.assistant.domain.model.Message
import com.vela.assistant.domain.model.MessageRole
import com.vela.assistant.domain.model.Attachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Mapper between domain models and data layer entities
 * Following Clean Architecture principles
 */
object ConversationMapper {

    fun toDomain(
        entity: ConversationEntity,
        messages: List<MessageEntity>
    ): Conversation {
        return Conversation(
            id = entity.id,
            title = entity.title,
            messages = messages.map { it.toDomain() },
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    fun toEntity(domain: Conversation): ConversationEntity {
        return ConversationEntity(
            id = domain.id,
            title = domain.title,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt
        )
    }

    private fun MessageEntity.toDomain(): Message {
        return Message(
            id = id,
            content = content,
            role = MessageRole.valueOf(role),
            timestamp = timestamp,
            attachments = try {
                if (attachments.isNotEmpty()) {
                    Json.decodeFromString<List<Attachment>>(attachments)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    fun Message.toEntity(conversationId: String): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            content = content,
            role = role.name,
            timestamp = timestamp,
            attachments = if (attachments.isNotEmpty()) {
                Json.encodeToString(attachments)
            } else {
                ""
            }
        )
    }
}
