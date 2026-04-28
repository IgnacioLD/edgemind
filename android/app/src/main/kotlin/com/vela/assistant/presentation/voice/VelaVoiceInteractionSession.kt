package com.vela.assistant.presentation.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import com.vela.assistant.R
import com.vela.assistant.data.local.AndroidTtsEngine
import com.vela.assistant.data.local.AudioRecorder
import com.vela.assistant.data.local.Gemma4ModelWrapper
import com.vela.assistant.domain.model.InferenceRequest
import com.vela.assistant.domain.model.InferenceResult
import com.vela.assistant.domain.repository.ModelRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

// Overlay session that runs when the user invokes the assistant (long-press home / assist
// gesture) once Vela is the device's default Assistant. The "wake word" is the system
// trigger itself — implementing a real always-on wake word would need Porcupine or similar.
//
// Choreographed flow inspired by the user's order-taking UX request:
//   1. Trigger fires → session shown.
//   2. If model isn't loaded yet, speak "Cargando" / "Loading" while preloading concurrently.
//   3. The moment the model is ready, recording starts immediately — no second prompt. The
//      status TextView flips to "Listening…" as the visual cue. Skipping the spoken "Dime"
//      removes a ~700 ms gap and is what the user explicitly requested.
//   4. Recording stops on mic-tap OR a hard 12-second cap (no VAD on-device).
//   5. We speak a short ack ("Vale" / "OK") so the user knows we heard them, then send audio
//      to Gemma; the final response is spoken via TTS; session auto-dismisses.
//
// Locale picks Spanish prompts when device locale is es-*; otherwise English.
//
// Hilt note: VoiceInteractionSession isn't an Android component Hilt can directly inject, so
// dependencies come via an EntryPoint accessed off the application context.
class VelaVoiceInteractionSession(
    context: Context,
) : VoiceInteractionSession(context) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun audioRecorder(): AudioRecorder
        fun tts(): AndroidTtsEngine
        fun modelRepository(): ModelRepository
        fun gemma(): Gemma4ModelWrapper
    }

    private val deps: Deps by lazy {
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var statusView: TextView? = null
    private var responseView: TextView? = null
    private var micButton: ImageButton? = null

    private var orchestratorJob: Job? = null
    @Volatile private var recording: Boolean = false
    @Volatile private var stopRequested: Boolean = false

    private val loadingPrompt = "Loading"
    private val ackPrompt = "OK"
    private val listeningStatus = "Listening…"
    private val thinkingStatus = "Thinking…"
    private val speakingStatus = "Speaking…"

    override fun onCreateContentView(): View {
        val view = layoutInflater.inflate(R.layout.voice_overlay, null)
        statusView = view.findViewById(R.id.voice_status)
        responseView = view.findViewById(R.id.voice_response)
        micButton = view.findViewById(R.id.voice_mic)

        // The mic button is now a "stop early" affordance during auto-listening. Tap to send
        // what was captured so far.
        micButton?.setOnClickListener {
            if (recording) stopRequested = true
        }

        view.findViewById<Button>(R.id.voice_close).setOnClickListener { finishGracefully() }
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        responseView?.text = ""
        orchestratorJob = scope.launch { runOrchestratedFlow() }
    }

    override fun onHide() {
        cancelAllWork()
        super.onHide()
    }

    private suspend fun runOrchestratedFlow() {
        try {
            // Step 1: load (or skip if already warm).
            if (!deps.gemma().isLoaded()) {
                setStatus(loadingPrompt)
                // Speak + preload concurrently. Whichever finishes first waits for the other.
                val ttsJob = scope.async { deps.tts().speakAndAwait(loadingPrompt) }
                val preloadJob = scope.async {
                    runCatching { deps.gemma().preload() }
                        .onFailure { Timber.e(it, "Voice session preload failed") }
                }
                ttsJob.await()
                preloadJob.await()
            } else {
                Timber.i("Model already loaded; skipping 'Cargando' prompt")
            }

            // Step 2: start recording right away. No spoken prompt — the status flip is the
            // cue. Cuts ~700 ms of dead air the user complained about.
            setStatus(listeningStatus)
            val pcm = recordWithCap()
            if (pcm.isEmpty()) {
                setStatus("I didn't hear anything.")
                delay(1500)
                finishGracefully()
                return
            }

            // Step 4: send to Gemma, stream the response on screen, speak the final text.
            setStatus(thinkingStatus)
            processAudio(pcm)
        } catch (e: Throwable) {
            Timber.e(e, "Orchestrated voice flow failed")
            setStatus("Error: ${e.message}")
            delay(2000)
            finishGracefully()
        }
    }

    private suspend fun recordWithCap(): ByteArray {
        if (!deps.audioRecorder().hasMicPermission()) {
            setStatus("Mic permission missing.")
            return ByteArray(0)
        }
        recording = true
        stopRequested = false
        // Hard cap as a safety net in case the user is in a noisy environment where the RMS
        // never drops below the silence threshold. VAD is the primary stop signal.
        val capJob = scope.launch {
            delay(MAX_RECORD_MS)
            stopRequested = true
        }
        return try {
            deps.audioRecorder().recordUntilStop(
                stopSignal = { stopRequested },
                silenceTimeoutMs = SILENCE_TIMEOUT_MS,
                minSpeechMs = MIN_SPEECH_MS,
                silenceRmsThreshold = SILENCE_RMS_THRESHOLD,
            )
        } finally {
            recording = false
            capJob.cancel()
        }
    }

    private suspend fun processAudio(pcm: ByteArray) {
        // Bridge the long silence between user-finished-speaking and final-response. Without
        // this, the user hears nothing for the full prefill+tool-execute+decode duration and
        // can't tell if the assistant heard them. Short ack ("Vale" / "OK") gives immediate
        // feedback and only delays inference by ~400ms — a clear net win for perceived latency.
        setStatus(thinkingStatus)
        deps.tts().speakAndAwait(ackPrompt)

        val accumulated = StringBuilder()
        deps.modelRepository()
            .runInference(InferenceRequest(prompt = "", audioPcm = pcm))
            .catch { error ->
                Timber.e(error, "Voice inference failed")
                setStatus("Error: ${error.message}")
            }
            .collect { result ->
                when (result) {
                    is InferenceResult.Loading -> Unit
                    is InferenceResult.Streaming -> {
                        accumulated.append(result.text)
                        responseView?.text = accumulated.toString()
                    }
                    is InferenceResult.Success -> {
                        responseView?.text = result.text
                        setStatus(speakingStatus)
                        // Match the voice to whatever language Gemma chose. Without this the
                        // overlay reads Spanish replies with the device's default English voice.
                        deps.tts().speakInDetectedLanguageAndAwait(result.text)
                        finishGracefully()
                    }
                    is InferenceResult.Error -> {
                        setStatus(result.message)
                        delay(2000)
                        finishGracefully()
                    }
                }
            }
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }

    private fun cancelAllWork() {
        recording = false
        stopRequested = true
        orchestratorJob?.cancel()
        deps.audioRecorder().stop()
        deps.tts().stop()
    }

    private fun finishGracefully() {
        cancelAllWork()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MAX_RECORD_MS = 12_000L
        const val SILENCE_TIMEOUT_MS = 1_500L  // ms of trailing silence before auto-stop
        const val MIN_SPEECH_MS = 700L         // require at least this much speech first
        const val SILENCE_RMS_THRESHOLD = 500  // sub-threshold = silence (16-bit signed)
    }
}
