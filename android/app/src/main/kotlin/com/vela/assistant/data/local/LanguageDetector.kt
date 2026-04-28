// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.data.local

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Picks a Locale to feed TextToSpeech.setLanguage() based on the actual content of an assistant
// reply. Without this, the TTS engine speaks Spanish text with an English voice (or vice versa)
// and the result sounds garbled. Runs entirely on-device — ML Kit's language-id package bundles
// its model into the AAR and does not call out to Google Play Services at runtime.
//
// Confidence threshold: 0.5 matches ML Kit's documented default. We're explicit about it so the
// behaviour doesn't shift if the SDK ever changes its built-in default. Below threshold the API
// returns "und" (undetermined), which we treat as "fall back to en-US".
@Singleton
class LanguageDetector @Inject constructor() {

    private val client by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                .build(),
        )
    }

    suspend fun detectLanguage(text: String): Locale {
        if (text.isBlank()) return Locale.US
        return suspendCancellableCoroutine { cont ->
            client.identifyLanguage(text)
                .addOnSuccessListener { tag ->
                    val locale = if (tag.isNullOrBlank() || tag == UNDETERMINED) {
                        Timber.i("Language detection: undetermined; falling back to en-US")
                        Locale.US
                    } else {
                        Locale.forLanguageTag(tag)
                    }
                    if (cont.isActive) cont.resume(locale)
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "Language detection failed; falling back to en-US")
                    if (cont.isActive) cont.resume(Locale.US)
                }
                .addOnCanceledListener {
                    if (cont.isActive) cont.resumeWithException(
                        kotlinx.coroutines.CancellationException("Language detection cancelled"),
                    )
                }
        }
    }

    private companion object {
        const val UNDETERMINED = "und"
        const val CONFIDENCE_THRESHOLD = 0.5f
    }
}
