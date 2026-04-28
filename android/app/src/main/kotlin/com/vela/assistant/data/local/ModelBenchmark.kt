// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.data.local

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Developer tool, NOT production logic. Runs a fixed prompt against whichever .litertlm is
// currently sitting in the app's external files dir, logs latency stats under the
// "VelaBenchmark" tag so you can `adb logcat -s VelaBenchmark` and pull the numbers out.
//
// The log line includes model_file (the on-disk filename) and model_size_bytes so the two
// .litertlm builds are unambiguously distinguishable: generic ≈ 2.58 GB, Qualcomm ≈ 3.29 GB.
// Even if you swap files via `adb push` and keep the same filename, the size byte-count tells
// you which one was actually loaded.
//
// To compare the two model files: run once, push the alternate .litertlm into place via adb
// (replacing the file at ModelFileManager.modelFile, keeping the same filename and deleting
// the .ok sentinel), kill+relaunch the app, run again. Same prompt, same backend cascade, so
// the numbers are comparable.
//
// Caveats: the benchmark prompt intentionally mixes a direct-answer ("What time is it?") with
// a tool call ("Set a timer for 5 minutes."), which means total time includes a tool execution
// round-trip. That's fine for the comparative use case (both model runs pay the same overhead),
// but it's not a pure decode-throughput number. For pure decode-throughput the prompt would
// need to be tool-disabled — out of scope for a one-shot dev tool.
@Singleton
class ModelBenchmark @Inject constructor(
    private val gemma: Gemma4ModelWrapper,
    private val fileManager: ModelFileManager,
) {
    suspend fun run(): BenchmarkResult? {
        // Cold-start each run: drop any persistent KV cache so we measure prefill from zero.
        // Without this, the first run after engine load is "cold" but a second run on the same
        // session would show a misleadingly small TTFT because the system prompt is cached.
        gemma.resetConversation()

        val backend = gemma.activeBackend()
        val modelFileName = fileManager.modelFile.name
        val modelSizeBytes = fileManager.modelFile.length()
        val started = System.currentTimeMillis()
        var firstTokenAt = -1L
        var chunkCount = 0
        var charCount = 0
        val accumulated = StringBuilder()

        val completed = withTimeoutOrNull(BENCHMARK_TIMEOUT_MS) {
            gemma.generate(prompt = BENCHMARK_PROMPT, audioPcm = null).collect { chunk ->
                when (chunk) {
                    is Gemma4ModelWrapper.Chunk.Token -> {
                        if (firstTokenAt < 0) firstTokenAt = System.currentTimeMillis() - started
                        chunkCount++
                        charCount += chunk.text.length
                        accumulated.append(chunk.text)
                    }
                    is Gemma4ModelWrapper.Chunk.Done -> Unit
                }
            }
            true
        } ?: false

        val totalMs = System.currentTimeMillis() - started
        val ttftMs = if (firstTokenAt >= 0) firstTokenAt else -1L
        val decodeMs = if (ttftMs > 0) totalMs - ttftMs else -1L
        val chunksPerSec = if (decodeMs > 0) chunkCount * 1000.0 / decodeMs else 0.0
        val charsPerSec = if (decodeMs > 0) charCount * 1000.0 / decodeMs else 0.0

        // Force Locale.ROOT so decimals always use '.' regardless of device locale — otherwise
        // a Spanish phone logs "18,46" and grep / awk pipelines downstream parse incorrectly.
        val message = String.format(
            Locale.ROOT,
            "model_file=%s model_size_bytes=%d backend=%s prompt='%s' ttft_ms=%d total_ms=%d decode_ms=%d chunks=%d chars=%d chunks_per_s=%.2f chars_per_s=%.2f response='%s'",
            modelFileName,
            modelSizeBytes,
            backend,
            BENCHMARK_PROMPT,
            ttftMs,
            totalMs,
            decodeMs,
            chunkCount,
            charCount,
            chunksPerSec,
            charsPerSec,
            accumulated.toString().take(160),
        )

        if (!completed) {
            Timber.tag(TAG).w("TIMEOUT %s", message)
            return null
        }

        Timber.tag(TAG).i(message)

        return BenchmarkResult(
            modelFileName = modelFileName,
            modelSizeBytes = modelSizeBytes,
            backend = backend,
            ttftMs = ttftMs,
            totalMs = totalMs,
            decodeMs = decodeMs,
            chunkCount = chunkCount,
            charCount = charCount,
            chunksPerSec = chunksPerSec,
            charsPerSec = charsPerSec,
        )
    }

    data class BenchmarkResult(
        val modelFileName: String,
        val modelSizeBytes: Long,
        val backend: String,
        val ttftMs: Long,
        val totalMs: Long,
        val decodeMs: Long,
        val chunkCount: Int,
        val charCount: Int,
        val chunksPerSec: Double,
        val charsPerSec: Double,
    )

    private companion object {
        const val TAG = "VelaBenchmark"
        const val BENCHMARK_PROMPT = "What time is it? Set a timer for 5 minutes."
        const val BENCHMARK_TIMEOUT_MS = 60_000L
    }
}
