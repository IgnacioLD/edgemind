package com.localai.assistant.presentation.chat

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.localai.assistant.domain.model.*
import com.localai.assistant.domain.usecase.CreateConversationUseCase
import com.localai.assistant.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for ChatViewModel
 * Tests UI state management and business logic
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var createConversationUseCase: CreateConversationUseCase
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sendMessageUseCase = mock()
        createConversationUseCase = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage updates UI state with user message immediately`() = runTest {
        // Given
        val conversation = Conversation(id = "test-id", title = "Test")
        whenever(createConversationUseCase.invoke(any())).thenReturn(Result.success(conversation))
        whenever(sendMessageUseCase.invoke(any(), any())).thenReturn(
            flowOf(InferenceResult.Loading)
        )

        viewModel = ChatViewModel(sendMessageUseCase, createConversationUseCase)
        advanceUntilIdle()

        // When
        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.messages).hasSize(1)
            assertThat(state.messages.first().content).isEqualTo("Hello")
            assertThat(state.messages.first().role).isEqualTo(MessageRole.USER)
        }
    }

    @Test
    fun `sendMessage shows loading state during inference`() = runTest {
        // Given
        val conversation = Conversation(id = "test-id", title = "Test")
        whenever(createConversationUseCase.invoke(any())).thenReturn(Result.success(conversation))
        whenever(sendMessageUseCase.invoke(any(), any())).thenReturn(
            flowOf(InferenceResult.Loading)
        )

        viewModel = ChatViewModel(sendMessageUseCase, createConversationUseCase)
        advanceUntilIdle()

        // When
        viewModel.sendMessage("Test")
        advanceUntilIdle()

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.isLoading).isTrue()
        }
    }

    @Test
    fun `sendMessage adds assistant response on success`() = runTest {
        // Given
        val conversation = Conversation(id = "test-id", title = "Test")
        whenever(createConversationUseCase.invoke(any())).thenReturn(Result.success(conversation))
        whenever(sendMessageUseCase.invoke(any(), any())).thenReturn(
            flowOf(
                InferenceResult.Success(
                    text = "AI Response",
                    tokensGenerated = 10,
                    inferenceTimeMs = 100
                )
            )
        )

        viewModel = ChatViewModel(sendMessageUseCase, createConversationUseCase)
        advanceUntilIdle()

        // When
        viewModel.sendMessage("User message")
        advanceUntilIdle()

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.messages).hasSize(2)
            assertThat(state.messages.last().role).isEqualTo(MessageRole.ASSISTANT)
            assertThat(state.messages.last().content).isEqualTo("AI Response")
            assertThat(state.isLoading).isFalse()
        }
    }
}
