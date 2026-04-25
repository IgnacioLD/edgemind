package com.localai.assistant.data.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Owns the long-lived LiteRT-LM Engine for Gemma 4 E2B.
// CPU-only: this device lacks libOpenCL.so so the GPU sampler crashes with "Can not find OpenCL
// library on this device" partway through decode. Audio adapter sections in the .litertlm are
// CPU-pinned anyway, so we keep the whole pipeline on CPU.
// Audio bytes coming in are raw 16 kHz mono PCM 16-bit signed LE; we wrap them in a WAV header
// because litertlm decodes audio via miniaudio, which sniffs by header — raw PCM fails with
// "miniaudio decoder, error code: -10". Empty audio is dropped.
//
// Conversations are recreated per turn for now; multi-turn memory is a future concern (Phase 3+
// when tool calling lands). cancelProcess() on the active conversation stops in-flight generation.
@Singleton
class Gemma4ModelWrapper @Inject constructor(
    private val fileManager: ModelFileManager,
) {
    sealed class Chunk {
        data class Token(val text: String) : Chunk()
        data class Done(val totalText: String) : Chunk()
    }

    private val initLock = Mutex()
    private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null

    fun isLoaded(): Boolean = engine != null

    suspend fun preload() {
        ensureLoaded()
    }

    private suspend fun ensureLoaded(): Engine = initLock.withLock {
        engine?.let { return@withLock it }

        check(fileManager.isModelPresent()) {
            "Gemma 4 model not present at ${fileManager.modelFile.absolutePath}; download must complete first."
        }

        Timber.i("Loading Gemma 4 from ${fileManager.modelFile.absolutePath}")
        val started = System.currentTimeMillis()
        val config = EngineConfig(
            modelPath = fileManager.modelFile.absolutePath,
            backend = Backend.CPU(),
            visionBackend = null,
            audioBackend = Backend.CPU(),
            maxNumTokens = MAX_TOKENS,
            maxNumImages = null,
            cacheDir = null,
        )
        val handle = withContext(Dispatchers.IO) {
            Engine(config).also { it.initialize() }
        }
        engine = handle
        Timber.i("Gemma 4 ready in ${System.currentTimeMillis() - started}ms")
        handle
    }

    fun generate(prompt: String, audioPcm: ByteArray? = null): Flow<Chunk> = flow {
        val handle = ensureLoaded()

        val conversation = handle.createConversation(
            ConversationConfig(
                systemInstruction = null,
                initialMessages = emptyList(),
                tools = emptyList(),
                samplerConfig = SamplerConfig(
                    /* topK = */ DEFAULT_TOP_K,
                    /* topP = */ DEFAULT_TOP_P,
                    /* temperature = */ DEFAULT_TEMPERATURE,
                    /* seed = */ DEFAULT_SEED,
                ),
                automaticToolCalling = false,
                channels = emptyList(),
                extraContext = emptyMap(),
            ),
        )
        activeConversation = conversation

        val parts = buildList<Content> {
            if (audioPcm != null && audioPcm.size >= MIN_AUDIO_BYTES) {
                val wav = PcmToWav.wrap(audioPcm)
                Timber.d("Adding audio: ${audioPcm.size} pcm bytes -> ${wav.size} wav bytes")
                add(Content.AudioBytes(wav))
            }
            // Per the gallery convention, text comes after media for "accurate last token".
            if (prompt.isNotEmpty()) {
                add(Content.Text(prompt))
            }
        }

        if (parts.isEmpty()) {
            conversation.close()
            activeConversation = null
            emit(Chunk.Done(""))
            return@flow
        }

        val accumulated = StringBuilder()
        try {
            conversation.sendMessageAsync(Contents.of(parts), emptyMap<String, Any>())
                .collect { message ->
                    val tokenText = message.extractText()
                    if (tokenText.isNotEmpty()) {
                        accumulated.append(tokenText)
                        emit(Chunk.Token(tokenText))
                    }
                }
            emit(Chunk.Done(accumulated.toString()))
        } finally {
            try {
                conversation.close()
            } catch (e: Exception) {
                Timber.w(e, "Error closing Conversation")
            }
            activeConversation = null
        }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        try {
            activeConversation?.let {
                // cancelProcess exists in the gallery API surface and aborts in-flight generation
                // natively. Reflective lookup keeps us forward-compatible across minor API shifts.
                val method = it.javaClass.methods.firstOrNull { m -> m.name == "cancelProcess" }
                method?.invoke(it)
            }
        } catch (e: Exception) {
            Timber.w(e, "cancelProcess failed")
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private fun Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private companion object {
        const val MAX_TOKENS = 2048
        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.95
        const val DEFAULT_SEED = 0
        // Drop accidental taps on the mic. 0.5 s of 16 kHz mono int16 = 16 KB.
        const val MIN_AUDIO_BYTES = 16_000
    }
}
