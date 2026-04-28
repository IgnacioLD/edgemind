// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vela.assistant.data.local.OnboardingPreferences
import com.vela.assistant.presentation.chat.ChatScreen
import com.vela.assistant.presentation.common.theme.VelaTheme
import com.vela.assistant.presentation.onboarding.OnboardingScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Single-Activity host. Routing between OnboardingScreen and ChatScreen is driven by the
// onboarding-complete StateFlow rather than Compose Navigation — the route graph would have
// exactly two nodes and back-handling for a one-shot first-run flow is simpler this way.
// "Replay onboarding" from the settings dialog flips the flag back to false, and this
// collectAsState pulls the activity into the onboarding flow without needing a manual restart.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var onboardingPrefs: OnboardingPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VelaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val onboardingComplete by onboardingPrefs.isComplete.collectAsState()
                    if (onboardingComplete) {
                        ChatScreen()
                    } else {
                        OnboardingScreen(onComplete = { onboardingPrefs.markComplete() })
                    }
                }
            }
        }
    }
}
