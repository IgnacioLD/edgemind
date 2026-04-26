package com.localai.assistant.presentation.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

// The voice-interaction-service XML config requires a recognitionService to point at, but
// EdgeMind doesn't run a separate STT step — Gemma 4 ingests the audio directly inside the
// assistant turn. This stub satisfies the framework requirement and reports "not available"
// so anything that tries to use us as a generic SpeechRecognizer fails fast.
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
