package com.localai.assistant.domain.model

data class InferenceRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val imageUri: String? = null,
    // Raw 16 kHz mono PCM 16-bit signed LE bytes when the user spoke instead of typed.
    // Null for text-only turns.
    val audioPcm: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InferenceRequest) return false
        return prompt == other.prompt &&
            maxTokens == other.maxTokens &&
            temperature == other.temperature &&
            imageUri == other.imageUri &&
            audioPcm.contentEqualsOrBothNull(other.audioPcm)
    }

    override fun hashCode(): Int {
        var result = prompt.hashCode()
        result = 31 * result + maxTokens
        result = 31 * result + temperature.hashCode()
        result = 31 * result + (imageUri?.hashCode() ?: 0)
        result = 31 * result + (audioPcm?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
