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

    @SuppressLint("MissingPermission") // checked at the call site via hasMicPermission()
    suspend fun recordUntilStop(stopSignal: () -> Boolean): ByteArray = withContext(Dispatchers.IO) {
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

        return@withContext try {
            rec.startRecording()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            val maxBytes = MAX_DURATION_SEC * SAMPLE_RATE * BYTES_PER_SAMPLE

            while (recording && !stopSignal() && out.size() < maxBytes) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) out.write(buffer, 0, read)
                else if (read < 0) {
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

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2 // 16-bit
        const val READ_CHUNK_BYTES = 4096
        const val MAX_DURATION_SEC = 30
    }
}
