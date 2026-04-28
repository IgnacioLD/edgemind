// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Persists whether the first-run onboarding has been completed. Backed by SharedPreferences with
// an in-memory StateFlow mirror so MainActivity can switch between OnboardingScreen and
// ChatScreen reactively (e.g. when the user invokes "Replay onboarding" from settings, the
// flag flips and the activity re-renders without needing a manual restart).
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isComplete = MutableStateFlow(prefs.getBoolean(KEY_COMPLETE, false))
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    fun markComplete() {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
        _isComplete.value = true
    }

    fun reset() {
        prefs.edit().putBoolean(KEY_COMPLETE, false).apply()
        _isComplete.value = false
    }

    private companion object {
        const val PREFS_NAME = "vela_onboarding"
        const val KEY_COMPLETE = "onboarding_complete"
    }
}
