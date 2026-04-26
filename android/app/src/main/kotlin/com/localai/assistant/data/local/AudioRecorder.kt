package com.localai.assistant.data.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// 16 kHz mono 16-bit PCM, raw LE bytes — the format MediaPipe LlmInferenceSession.addAudio expects
// for Gemma audio modality. Caps at 30 s to match the model's max audio segment.
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var record: AudioRecord? = null
    @Volatile private var recording = false

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Records PCM until any of:
    //   • the caller's stopSignal returns true (push-to-talk release, hard timer cap, etc.)
    //   • silence-based auto-stop fires (only if silenceTimeoutMs > 0)
    //   • the model's 30s hard limit is reached
    //
    // Silence detection is amplitude/RMS based — primitive but cheap and good enough as a VAD
    // stand-in. We require at least minSpeechMs of audio above the threshold before any
    // silence-based stop is allowed, so the recording doesn't terminate before the user has
    // even started speaking. After speech onset, silenceTimeoutMs of consecutive sub-threshold
    // chunks (default 1.5 s) ends the take.
    @SuppressLint("MissingPermission") // checked at the call site via hasMicPermission()
    suspend fun recordUntilStop(
        stopSignal: () -> Boolean,
        silenceTimeoutMs: Long = 0L,
        minSpeechMs: Long = 700L,
        silenceRmsThreshold: Int = 500,
    ): ByteArray = withContext(Dispatchers.IO) {
        check(hasMicPermission()) { "RECORD_AUDIO permission not granted" }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuf, READ_CHUNK_BYTES)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        record = rec
        recording = true

        val vadEnabled = silenceTimeoutMs > 0L
        var consecutiveSilenceMs = 0L
        var speechMs = 0L

        return@withContext try {
            rec.startRecording()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            val maxBytes = MAX_DURATION_SEC * SAMPLE_RATE * BYTES_PER_SAMPLE

            while (recording && !stopSignal() && out.size() < maxBytes) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    if (vadEnabled) {
                        val chunkMs = (read.toLong() / BYTES_PER_SAMPLE) * 1000L / SAMPLE_RATE
                        val rms = computeRms(buffer, read)
                        if (rms >= silenceRmsThreshold) {
                            speechMs += chunkMs
                            consecutiveSilenceMs = 0L
                        } else {
                            consecutiveSilenceMs += chunkMs
                        }
                        if (speechMs >= minSpeechMs && consecutiveSilenceMs >= silenceTimeoutMs) {
                            Timber.i(
                                "VAD stop: speech=${speechMs}ms, trailingSilence=${consecutiveSilenceMs}ms",
                            )
                            break
                        }
                    }
                } else if (read < 0) {
                    Timber.w("AudioRecord.read returned $read")
                    break
                }
            }
            out.toByteArray().also {
                Timber.i("Audio captured: ${it.size} bytes (${it.size / BYTES_PER_SAMPLE / SAMPLE_RATE.toFloat()}s)")
            }
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
            record = null
            recording = false
        }
    }

    fun stop() {
        recording = false
    }

    // RMS over int16 little-endian PCM. Returns an integer in 0..32767. Speech typically
    // sits around 1000-5000 in our recording chain; quiet background room is usually <300.
    private fun computeRms(buf: ByteArray, length: Int): Int {
        val sampleCount = length / BYTES_PER_SAMPLE
        if (sampleCount == 0) return 0
        var sumSquares = 0.0
        var i = 0
        while (i < length - 1) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += (sample.toDouble() * sample.toDouble())
            i += BYTES_PER_SAMPLE
        }
        return sqrt(sumSquares / sampleCount).toInt()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2 // 16-bit
        const val READ_CHUNK_BYTES = 4096
        const val MAX_DURATION_SEC = 30
    }
}
