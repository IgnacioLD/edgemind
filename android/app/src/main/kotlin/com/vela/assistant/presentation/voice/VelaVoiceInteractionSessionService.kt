package com.vela.assistant.presentation.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

// Factory that creates a fresh session every time the user invokes the assistant
// (long-press home, assist gesture, "Hey Google"-style trigger when role is granted).
class VelaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return VelaVoiceInteractionSession(this)
    }
}
