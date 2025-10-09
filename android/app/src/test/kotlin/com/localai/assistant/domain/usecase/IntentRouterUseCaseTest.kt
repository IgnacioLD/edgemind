package com.localai.assistant.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.localai.assistant.domain.model.Attachment
import com.localai.assistant.domain.model.AttachmentType
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.model.ModelType
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for IntentRouterUseCase
 */
class IntentRouterUseCaseTest {

    private lateinit var intentRouter: IntentRouterUseCase

    @Before
    fun setup() {
        intentRouter = IntentRouterUseCase()
    }

    @Test
    fun `routeIntent returns VISION_DOCUMENT when message has image attachment`() {
        // Given
        val message = Message(
            content = "What is this?",
            role = MessageRole.USER,
            attachments = listOf(
                Attachment(
                    id = "1",
                    type = AttachmentType.IMAGE,
                    uri = "file:///image.jpg",
                    mimeType = "image/jpeg"
                )
            )
        )

        // When
        val result = intentRouter.routeIntent(message)

        // Then
        assertThat(result).isEqualTo(ModelType.VISION_DOCUMENT)
    }

    @Test
    fun `routeIntent returns VISION_DOCUMENT when message contains document keywords`() {
        // Given
        val messages = listOf(
            Message(content = "Scan this invoice", role = MessageRole.USER),
            Message(content = "Extract data from this PDF", role = MessageRole.USER),
            Message(content = "Read this receipt", role = MessageRole.USER)
        )

        // When & Then
        messages.forEach { message ->
            val result = intentRouter.routeIntent(message)
            assertThat(result).isEqualTo(ModelType.VISION_DOCUMENT)
        }
    }

    @Test
    fun `routeIntent returns TEXT_GENERAL for general questions`() {
        // Given
        val messages = listOf(
            Message(content = "What is 2 + 2?", role = MessageRole.USER),
            Message(content = "Tell me about photosynthesis", role = MessageRole.USER),
            Message(content = "How are you?", role = MessageRole.USER)
        )

        // When & Then
        messages.forEach { message ->
            val result = intentRouter.routeIntent(message)
            assertThat(result).isEqualTo(ModelType.TEXT_GENERAL)
        }
    }
}
