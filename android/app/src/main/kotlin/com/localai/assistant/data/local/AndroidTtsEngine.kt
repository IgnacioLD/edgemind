package com.localai.assistant.data.local

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Speaks final assistant responses via the platform TTS engine.
// Lazy: doesn't initialize TextToSpeech until first speak() call.
@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    fun speak(text: String) {
        if (text.isBlank()) return
        ensureInitialized {
            tts?.stop()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
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
                    ready = true
                    onReady()
                } else {
                    Timber.e("TTS init failed: status=$status")
                }
            }
        }
    }

    private companion object {
        const val UTTERANCE_ID = "edgemind-assistant-reply"
    }
}
