// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.data.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Owns the long-lived LiteRT-LM Engine AND a single persistent Conversation for Gemma 4 E2B.
//
// Persistent conversation: the previous design recreated it per turn, which forced the CPU to
// re-prefill the system prompt + serialized tool schemas (~600-900 tokens) every message. Now the
// conversation is built once and reused across turns; the KV cache stays warm and only the new
// user message is prefilled per turn. Time grounding is supplied per-turn as a `[Now: …]` prefix
// on the user message instead of being baked into the (now static) system instruction.
//
// Backend selection: try Backend.GPU() first, fall back to Backend.CPU() on init failure. The
// `<uses-native-library>` entries in AndroidManifest.xml (libvndksupport.so / libOpenCL.so) are
// what allow Backend.GPU() to actually find OpenCL on devices that have it — without those the
// platform linker hides the lib from the app. On devices without OpenCL (e.g. this phone), GPU
// init throws and we land on CPU. No manual System.load probing — the docs say the manifest
// declarations are the supported way.
//
// Audio bytes coming in are raw 16 kHz mono PCM 16-bit signed LE; we wrap them in a WAV header
// because litertlm decodes audio via miniaudio, which sniffs by header — raw PCM fails with
// "miniaudio decoder, error code: -10". Empty audio is dropped.
@Singleton
class Gemma4ModelWrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileManager: ModelFileManager,
    private val toolProviders: Set<@JvmSuppressWildcards ToolProvider>,
) {
    sealed class Chunk {
        data class Token(val text: String) : Chunk()
        data class Done(val totalText: String) : Chunk()
    }

    private val initLock = Mutex()
    private var engine: Engine? = null
    @Volatile private var persistentConversation: Conversation? = null
    @Volatile private var activeBackendId: String = "uninit"

    // Turn counter feeding the auto-reset heuristic. Guarded by initLock; only mutated inside it.
    private var turnCount = 0

    // One-shot signal the UI subscribes to so it can surface a "conversation refreshed" notice.
    // Buffer = 1 with no replay so a notice doesn't get dropped if the collector is briefly busy,
    // but a previously-fired notice that nobody saw isn't replayed when a new collector attaches.
    private val _autoResetEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val autoResetEvents: SharedFlow<Unit> = _autoResetEvents.asSharedFlow()

    fun isLoaded(): Boolean = engine != null

    // Backend the engine actually loaded on. Surfaced for diagnostic tooling (e.g. the
    // benchmark log) — production code should not branch on this.
    fun activeBackend(): String = activeBackendId

    // Warms BOTH the engine and the persistent conversation at app startup so the first user
    // message doesn't pay system-prompt + tool-schema prefill cost.
    suspend fun preload() {
        ensureConversation()
    }

    // Force the WHOLE function onto Dispatchers.IO. Without this wrapper, callers from the Main
    // dispatcher (e.g. ChatViewModel.preloadModel via viewModelScope.launch) end up running file
    // existence checks and the engine init on the UI thread, which causes "Skipped 33 frames"
    // and ANRs.
    private suspend fun ensureLoaded(): Engine = withContext(Dispatchers.IO) {
        initLock.withLock {
            engine?.let { return@withLock it }

            check(fileManager.isModelPresent()) {
                "Gemma 4 model not present at ${fileManager.modelFile.absolutePath}; download must complete first."
            }

            // Backend cascade: NPU → GPU → CPU. NPU is first because the Qualcomm-optimized
            // .litertlm builds have their main LLM section compiled exclusively for NPU and
            // refuse to load on GPU/CPU. NPU init throws on devices that don't expose a
            // Hexagon/QNN backend at runtime — the catch swallows it and we move on to GPU
            // for everyone else (the generic .litertlm has GPU + CPU sections).
            val attempts = listOf<Pair<String, () -> Backend>>(
                "npu" to { Backend.NPU() },
                "gpu" to { Backend.GPU() },
                "cpu" to { Backend.CPU() },
            )
            var lastError: Throwable? = null
            for ((id, backendFactory) in attempts) {
                Timber.i("Loading Gemma 4 from ${fileManager.modelFile.absolutePath} (backend=$id)")
                val started = System.currentTimeMillis()
                try {
                    val config = EngineConfig(
                        modelPath = fileManager.modelFile.absolutePath,
                        backend = backendFactory(),
                        visionBackend = null,
                        audioBackend = Backend.CPU(),
                        maxNumTokens = MAX_TOKENS,
                        maxNumImages = null,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    val handle = Engine(config).also { it.initialize() }
                    engine = handle
                    activeBackendId = id
                    Timber.i("Gemma 4 ready on $id in ${System.currentTimeMillis() - started}ms")
                    return@withLock handle
                } catch (e: Throwable) {
                    Timber.w(e, "Engine init failed on backend=$id; trying next")
                    lastError = e
                }
            }
            throw IllegalStateException("All backends failed to initialize", lastError)
        }
    }

    // Lazily build the persistent Conversation. Mutex is non-reentrant, so call ensureLoaded()
    // BEFORE acquiring initLock here (it acquires + releases internally).
    private suspend fun ensureConversation(): Conversation = withContext(Dispatchers.IO) {
        val handle = ensureLoaded()
        initLock.withLock {
            persistentConversation?.let { return@withLock it }

            val tools = toolProviders.toList()
            Timber.i("Creating persistent conversation with ${tools.size} tool providers")
            val started = System.currentTimeMillis()
            val conversation = handle.createConversation(
                ConversationConfig(
                    systemInstruction = systemInstruction(),
                    initialMessages = emptyList(),
                    tools = tools,
                    samplerConfig = SamplerConfig(
                        /* topK = */ DEFAULT_TOP_K,
                        /* topP = */ DEFAULT_TOP_P,
                        /* temperature = */ DEFAULT_TEMPERATURE,
                        /* seed = */ DEFAULT_SEED,
                    ),
                    automaticToolCalling = tools.isNotEmpty(),
                    channels = emptyList(),
                    extraContext = emptyMap(),
                ),
            )
            persistentConversation = conversation
            Timber.i("Persistent conversation ready in ${System.currentTimeMillis() - started}ms")
            conversation
        }
    }

    fun generate(prompt: String, audioPcm: ByteArray? = null): Flow<Chunk> = flow {
        Timber.i("generate() entered (prompt.len=${prompt.length}, audio=${audioPcm?.size ?: 0} bytes)")

        // KV cache eventually accumulates enough mis-transcribed audio / off-topic context to
        // produce 1-token nonsense. Periodic auto-reset keeps responses fresh without losing
        // the warm engine — the next ensureConversation() rebuilds dialogue history only.
        val didAutoReset = initLock.withLock {
            if (turnCount >= MAX_TURNS_BEFORE_RESET) {
                runCatching { persistentConversation?.close() }
                persistentConversation = null
                turnCount = 0
                true
            } else false
        }
        if (didAutoReset) {
            Timber.i("Auto-reset KV cache at turn $MAX_TURNS_BEFORE_RESET")
            _autoResetEvents.tryEmit(Unit)
        }

        val conversation = ensureConversation()
        Timber.i("generate() conversation ready (backend=$activeBackendId)")

        val timeContext = currentTimeContext()
        val parts = buildList<Content> {
            if (audioPcm != null && audioPcm.size >= MIN_AUDIO_BYTES) {
                val wav = PcmToWav.wrap(audioPcm)
                Timber.d("Adding audio: ${audioPcm.size} pcm bytes -> ${wav.size} wav bytes")
                add(Content.AudioBytes(wav))
            }
            // Per the gallery convention, text comes after media for "accurate last token".
            // Always prepend a [Now: …] line so the model has fresh time grounding without
            // having to re-prefill the system prompt.
            val textBody = if (prompt.isEmpty()) timeContext else "$timeContext\n$prompt"
            add(Content.Text(textBody))
        }

        val accumulated = StringBuilder()
        var messageIndex = 0
        try {
            Timber.i("generate() calling sendMessageAsync with ${parts.size} content parts")
            val sendStart = System.currentTimeMillis()
            conversation.sendMessageAsync(Contents.of(parts), emptyMap<String, Any>())
                .collect { message ->
                    if (messageIndex == 0) {
                        Timber.i("generate() first message arrived ${System.currentTimeMillis() - sendStart}ms after sendMessageAsync")
                    }
                    messageIndex++
                    val contentTypes = message.contents.contents.joinToString(",") { it::class.simpleName.orEmpty() }
                    val toolNames = message.toolCalls.joinToString(",") { it.name }
                    val tokenText = message.extractText()
                    Timber.d(
                        "msg[$messageIndex] contents=[$contentTypes] " +
                            "toolCalls=[$toolNames] " +
                            "channels=${message.channels.keys} " +
                            "textLen=${tokenText.length} text=${tokenText.take(120)}",
                    )
                    if (tokenText.isNotEmpty()) {
                        accumulated.append(tokenText)
                        emit(Chunk.Token(tokenText))
                    }
                }
            Timber.i("sendMessageAsync done: $messageIndex messages, ${accumulated.length} text chars")
            initLock.withLock { turnCount++ }
            emit(Chunk.Done(accumulated.toString()))
        } catch (e: Throwable) {
            // Mid-generation failure leaves conversation history in an uncertain state — drop it
            // so the next turn rebuilds. The cache-counter is meaningless against a missing
            // conversation, so reset it too.
            Timber.w(e, "Generation failed on backend=$activeBackendId; dropping persistent conversation")
            runCatching { persistentConversation?.close() }
            persistentConversation = null
            initLock.withLock { turnCount = 0 }
            throw e
        }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        try {
            persistentConversation?.let {
                // cancelProcess exists in the gallery API surface and aborts in-flight generation
                // natively. Reflective lookup keeps us forward-compatible across minor API shifts.
                val method = it.javaClass.methods.firstOrNull { m -> m.name == "cancelProcess" }
                method?.invoke(it)
            }
        } catch (e: Exception) {
            Timber.w(e, "cancelProcess failed")
        }
    }

    // Drops the persistent Conversation so the next turn rebuilds with a clean KV cache. Keeps
    // the underlying Engine alive — only the dialogue history goes away. Call this when the
    // conversation has degraded (model collapsing into 1-token responses, off-topic gibberish,
    // etc.) which can happen after enough turns when accumulated bad audio interpretations
    // poison the cache.
    suspend fun resetConversation() {
        initLock.withLock {
            Timber.i("resetConversation: dropping persistent conversation")
            runCatching { persistentConversation?.close() }
            persistentConversation = null
            turnCount = 0
        }
    }

    fun close() {
        runCatching { persistentConversation?.close() }
        persistentConversation = null
        engine?.close()
        engine = null
    }

    private fun Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    // Static behavioral guidance only. Time grounding lives in a per-turn `[Now: …]` prefix on
    // the user message so we don't have to re-prefill the system prompt every turn — that prefill
    // was the dominant latency hit on CPU.
    //
    // Thinking mode is disabled because we never emit the `<|think|>` trigger token at the start
    // of the system prompt (per the Gemma 4 model card, that's the toggle). For E2B/E4B that means
    // no thought tags are emitted at all.
    private fun systemInstruction(): Contents = Contents.of(
        listOf(
            Content.Text(
                """
                You are Vela, a private voice assistant on the user's phone.
                Each user message starts with [Now: <date/time/zone>]; interpret words like "today", "tomorrow", "tonight", "in two hours" relative to that. Never use any other date.
                Calendar event times must be ISO 8601 LOCAL format with no timezone suffix (example: 2026-04-26T19:00:00). Treat them as the [Now] timezone.
                For greetings and general-knowledge questions, reply directly without calling any tool. Call a tool only when the user explicitly asks for an action (timer, music, calendar read/create, contact lookup, app launch, flashlight, volume) or for current information you cannot know (then use search_web).
                After a tool returns, ALWAYS write a short natural-language reply describing what you did or found. Never end your turn with only a tool call.
                You are Vela, a private on-device AI assistant. Reply in the same language the user speaks. Be concise and helpful.
                Do not produce reasoning, thinking, analysis, scratchpad, or chain-of-thought tags.
                """.trimIndent(),
            ),
        ),
    )

    private fun currentTimeContext(): String {
        val now = ZonedDateTime.now()
        val human = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z"))
        return "[Now: $human (timezone ${now.zone.id})]"
    }

    private companion object {
        const val MAX_TOKENS = 2048
        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.95
        const val DEFAULT_SEED = 0
        // Drop accidental taps on the mic. 0.5 s of 16 kHz mono int16 = 16 KB.
        const val MIN_AUDIO_BYTES = 16_000
        // After this many successful turns, drop the persistent conversation so the next turn
        // rebuilds against a fresh KV cache. Calibrated against observed quality drift on
        // Gemma 4 E2B — somewhere past 25 turns the cache reliably starts emitting 1-token
        // garbage. 20 leaves headroom and keeps the user's session from collapsing mid-task.
        const val MAX_TURNS_BEFORE_RESET = 20
    }
}
