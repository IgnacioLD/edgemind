package com.localai.assistant.presentation.voice

import android.service.voice.VoiceInteractionService
import timber.log.Timber

// Empty system-binding service. Android binds this when the user picks EdgeMind as the device's
// default Assistant via RoleManager.ROLE_ASSISTANT. Real work happens in the SessionService —
// this class exists because the framework requires a VoiceInteractionService implementation to
// declare the metadata XML that points to the SessionService.
class EdgeMindVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Timber.i("EdgeMindVoiceInteractionService onReady — assistant role granted")
    }
}
