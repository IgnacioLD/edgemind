package com.vela.assistant.data.local

import java.nio.ByteBuffer
import java.nio.ByteOrder

// LiteRT-LM sends audio bytes through miniaudio, which decodes by header — raw PCM yields
// "INTERNAL: Failed to initialize miniaudio decoder, error code: -10". Wrap with a 44-byte WAV
// header so miniaudio recognizes the format. Input is 16 kHz mono int16 LE PCM (what AudioRecorder
// produces); output is the same bytes prefixed with the canonical RIFF/WAVE header.
internal object PcmToWav {

    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val PCM_FORMAT: Short = 1
    private const val HEADER_BYTES = 44

    fun wrap(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign: Short = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()
        val dataSize = pcm.size
        val chunkSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(chunkSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // fmt chunk size
        buffer.putShort(PCM_FORMAT)
        buffer.putShort(CHANNELS)
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(BITS_PER_SAMPLE)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        buffer.put(pcm)
        return buffer.array()
    }
}
