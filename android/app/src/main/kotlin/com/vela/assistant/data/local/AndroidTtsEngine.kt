package com.vela.assistant.data.local

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
    private val languageDetector: LanguageDetector,
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
            val id = "vela-${idCounter.incrementAndGet()}"
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

    // Detect the language of the supplied text, set the TTS voice to match (with en-US as a
    // safety net), then speak — returning when the utterance is fully done. Use this for the
    // model's final response so a Spanish reply gets a Spanish voice instead of being mangled
    // by the device default. Short fixed prompts ("OK", "Loading") should keep using the
    // plain speak/speakAndAwait variants — they're language-neutral and there's no point
    // paying a detection round-trip on them.
    suspend fun speakInDetectedLanguageAndAwait(text: String) {
        if (text.isBlank()) return
        val locale = languageDetector.detectLanguage(text)
        suspendCancellableCoroutine<Unit> { cont ->
            ensureInitialized {
                applyLanguageOrFallback(locale)
                val id = "vela-${idCounter.incrementAndGet()}"
                pendingCallbacks[id] = { if (cont.isActive) cont.resume(Unit) }
                tts?.stop()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
    }

    private fun applyLanguageOrFallback(locale: Locale) {
        val handle = tts ?: return
        val result = handle.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.w("TTS locale ${locale.toLanguageTag()} unavailable (result=$result); falling back to en-US")
            handle.setLanguage(Locale.US)
        }
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
                    // Hardcoded to English while the app is English-only. If we re-introduce
                    // localized voices we'd flip back to Locale.getDefault() with this as fallback.
                    val result = tts?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.w("TTS US English unavailable on this device")
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
