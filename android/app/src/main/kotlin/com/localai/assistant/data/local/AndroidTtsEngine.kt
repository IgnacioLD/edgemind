package com.localai.assistant.data.local

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Speaks final assistant responses via the platform TTS engine.
// Lazy: doesn't initialize TextToSpeech until first speak() call.
@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pendingCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val idCounter = AtomicInteger()

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        ensureInitialized {
            val id = "edgemind-${idCounter.incrementAndGet()}"
            if (onDone != null) pendingCallbacks[id] = onDone
            tts?.stop()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    // Coroutine convenience: suspends until the utterance finishes (or errors). Used by the
    // VoiceInteractionSession to choreograph "Cargando" → preload → "Dime" → record.
    suspend fun speakAndAwait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        speak(text) { if (cont.isActive) cont.resume(Unit) }
    }

    fun stop() {
        // Drop any pending callbacks — caller is intentionally cutting speech short.
        pendingCallbacks.clear()
        tts?.stop()
    }

    fun shutdown() {
        pendingCallbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun ensureInitialized(onReady: () -> Unit) {
        if (ready) {
            onReady()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val locale = Locale.getDefault()
                    val result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.w("TTS locale $locale unavailable, falling back to US English")
                        tts?.setLanguage(Locale.US)
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { pendingCallbacks.remove(it)?.invoke() }
                        }
                        @Deprecated("Required by base class")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { pendingCallbacks.remove(it)?.invoke() }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let { pendingCallbacks.remove(it)?.invoke() }
                        }
                    })
                    ready = true
                    onReady()
                } else {
                    Timber.e("TTS init failed: status=$status")
                }
            }
        }
    }
}
