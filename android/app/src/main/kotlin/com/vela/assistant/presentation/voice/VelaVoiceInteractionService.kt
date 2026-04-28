package com.vela.assistant.presentation.voice

import android.service.voice.VoiceInteractionService
import timber.log.Timber

// Empty system-binding service. Android binds this when the user picks Vela as the device's
// default Assistant via RoleManager.ROLE_ASSISTANT. Real work happens in the SessionService —
// this class exists because the framework requires a VoiceInteractionService implementation to
// declare the metadata XML that points to the SessionService.
class VelaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Timber.i("VelaVoiceInteractionService onReady — assistant role granted")
    }
}
