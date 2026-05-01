package com.vela.assistant.data.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
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
import kotlin.math.PI
import kotlin.math.sin
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

    // ── Confirmation chime ─────────────────────────────────────────────────────────────────
    // Replaces the older spoken "OK" acknowledgment that played when the user released the
    // mic. A short two-note arpeggio (A5 -> E6, perfect fifth) sounds like a modern UI ack —
    // think iOS message-sent or chat delivered — without us having to ship a wav asset.
    // Synthesized once into a 16-bit PCM buffer and played through AudioTrack in STATIC mode;
    // the track is released ~250 ms after play() since each chime is one-shot.
    fun playConfirmChime() {
        try {
            val pcm = chimePcm
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CHIME_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                pcm.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track.write(pcm, 0, pcm.size)
            track.play()
            chimeReleaseHandler.postDelayed({
                runCatching { track.stop() }
                runCatching { track.release() }
            }, 350)
        } catch (t: Throwable) {
            Timber.w(t, "playConfirmChime failed")
        }
    }

    private val chimeReleaseHandler by lazy { Handler(Looper.getMainLooper()) }

    private val chimePcm: ShortArray by lazy { synthesizeChime() }

    private fun synthesizeChime(): ShortArray {
        val sampleRate = CHIME_SAMPLE_RATE
        val note1Ms = 80
        val note2Ms = 130
        val gapMs = 10
        val n1 = sampleRate * note1Ms / 1000
        val n2 = sampleRate * note2Ms / 1000
        val gap = sampleRate * gapMs / 1000
        val total = n1 + gap + n2
        val buf = ShortArray(total)
        val attack = sampleRate * 4 / 1000      // 4 ms attack
        val release = sampleRate * 30 / 1000    // 30 ms release tail
        val amp = 0.32                          // headroom; keep it pleasant, not jarring

        fun env(i: Int, len: Int): Double {
            val a = if (i < attack) i.toDouble() / attack else 1.0
            val r = if (i > len - release) (len - i).toDouble() / release else 1.0
            return a.coerceAtMost(1.0) * r.coerceAtLeast(0.0)
        }

        // A5 = 880 Hz
        for (i in 0 until n1) {
            val s = sin(2.0 * PI * 880.0 * i / sampleRate) * env(i, n1) * amp
            buf[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        // E6 = 1318.5 Hz (perfect fifth above A5)
        for (i in 0 until n2) {
            val s = sin(2.0 * PI * 1318.5 * i / sampleRate) * env(i, n2) * amp
            buf[n1 + gap + i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    private companion object {
        const val CHIME_SAMPLE_RATE = 44_100
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
