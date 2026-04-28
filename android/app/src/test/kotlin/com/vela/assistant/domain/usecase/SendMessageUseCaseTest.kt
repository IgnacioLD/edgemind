package com.vela.assistant.domain.usecase

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.vela.assistant.domain.model.InferenceResult
import com.vela.assistant.domain.model.Message
import com.vela.assistant.domain.model.MessageRole
import com.vela.assistant.domain.repository.ConversationRepository
import com.vela.assistant.domain.repository.ModelRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SendMessageUseCaseTest {

    private lateinit var modelRepository: ModelRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var useCase: SendMessageUseCase

    @Before
    fun setup() {
        modelRepository = mock()
        conversationRepository = mock()
        useCase = SendMessageUseCase(modelRepository, conversationRepository)
    }

    @Test
    fun `sendMessage returns success when inference succeeds`() = runTest {
        val message = Message(content = "Test message", role = MessageRole.USER)
        val conversationId = "test-conversation-id"

        whenever(modelRepository.runInference(any())).thenReturn(
            flowOf(
                InferenceResult.Success(
                    text = "Response",
                    tokensGenerated = 10,
                    inferenceTimeMs = 100,
                ),
            ),
        )
        whenever(conversationRepository.addMessage(any(), any())).thenReturn(Result.success(Unit))

        useCase(conversationId, message).test {
            val result = awaitItem()
            assertThat(result).isInstanceOf(InferenceResult.Success::class.java)
            assertThat((result as InferenceResult.Success).text).isEqualTo("Response")
            awaitComplete()
        }
    }
}
