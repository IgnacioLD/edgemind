package com.vela.assistant.presentation.chat

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.vela.assistant.data.local.AndroidTtsEngine
import com.vela.assistant.data.local.AudioRecorder
import com.vela.assistant.data.local.Gemma4ModelWrapper
import com.vela.assistant.domain.model.*
import com.vela.assistant.domain.repository.ModelAvailabilityRepository
import com.vela.assistant.domain.usecase.CreateConversationUseCase
import com.vela.assistant.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
    private lateinit var modelAvailability: ModelAvailabilityRepository
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var tts: AndroidTtsEngine
    private lateinit var gemma: Gemma4ModelWrapper
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sendMessageUseCase = mock()
        createConversationUseCase = mock()
        modelAvailability = mock()
        audioRecorder = mock()
        tts = mock()
        gemma = mock()
        whenever(gemma.isLoaded()).thenReturn(true)
        whenever(modelAvailability.status).thenReturn(MutableStateFlow(ModelStatus.Ready))
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
        whenever(sendMessageUseCase.invoke(any(), any(), anyOrNull())).thenReturn(
            flowOf(InferenceResult.Loading)
        )

        viewModel = ChatViewModel(sendMessageUseCase, createConversationUseCase, modelAvailability, audioRecorder, tts, gemma)
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
        whenever(sendMessageUseCase.invoke(any(), any(), anyOrNull())).thenReturn(
            flowOf(InferenceResult.Loading)
        )

        viewModel = ChatViewModel(sendMessageUseCase, createConversationUseCase, modelAvailability, audioRecorder, tts, gemma)
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
        whenever(sendMessageUseCase.invoke(any(), any(), anyOrNull())).thenReturn(
            flowOf(
                InferenceResult.Success(
                    text = "AI Response",
                    tokensGenerated = 10,
                    inferenceTimeMs = 100
                )
            )
        )

        viewModel = ChatViewModel(sendMessageUseCase, createConversationUseCase, modelAvailability, audioRecorder, tts, gemma)
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
