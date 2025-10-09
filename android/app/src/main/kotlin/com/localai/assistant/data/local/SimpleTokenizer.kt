package com.localai.assistant.data.local

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Simple tokenizer for text-only inference
 * Loads vocabulary from assets and performs basic tokenization
 */
class SimpleTokenizer(
    private val context: Context,
    private val vocabPath: String = "models/vocab.json"
) {

    private var tokenToId: Map<String, Long> = emptyMap()
    private var idToToken: Map<Long, String> = emptyMap()

    // Special tokens
    private val bosToken = "<s>"
    private val eosToken = "</s>"
    private val unkToken = "<unk>"
    private val padToken = "<pad>"

    private var bosTokenId: Long = 1
    private var eosTokenId: Long = 2
    private var unkTokenId: Long = 0
    private var padTokenId: Long = 0

    /**
     * Initialize tokenizer by loading vocabulary
     */
    fun initialize(): Result<Unit> {
        return try {
            Timber.d("Loading vocabulary from $vocabPath...")

            val json = loadVocabFile()
            val vocabJson = JSONObject(json)

            val vocab = mutableMapOf<String, Long>()
            val keys = vocabJson.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = vocabJson.getLong(token)
                vocab[token] = id
            }

            tokenToId = vocab
            idToToken = vocab.entries.associate { it.value to it.key }

            // Get special token IDs
            bosTokenId = tokenToId[bosToken] ?: 1
            eosTokenId = tokenToId[eosToken] ?: 2
            unkTokenId = tokenToId[unkToken] ?: 0
            padTokenId = tokenToId[padToken] ?: 0

            Timber.i("✅ Vocabulary loaded: ${tokenToId.size} tokens")
            Timber.d("Special tokens: BOS=$bosTokenId, EOS=$eosTokenId, UNK=$unkTokenId")

            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "Failed to load vocabulary")

            // Create minimal fallback vocabulary
            createFallbackVocab()

            Result.failure(e)
        }
    }

    /**
     * Create minimal fallback vocabulary for testing
     */
    private fun createFallbackVocab() {
        Timber.w("Using fallback vocabulary (limited functionality)")

        val fallback = mutableMapOf<String, Long>()
        fallback[padToken] = 0
        fallback[unkToken] = 1
        fallback[bosToken] = 2
        fallback[eosToken] = 3

        // Add basic ASCII
        var id = 4L
        for (c in ' '..'~') {
            fallback[c.toString()] = id++
        }

        tokenToId = fallback
        idToToken = fallback.entries.associate { it.value to it.key }

        bosTokenId = 2
        eosTokenId = 3
        unkTokenId = 1
        padTokenId = 0
    }

    /**
     * Encode text to token IDs
     */
    fun encode(
        text: String,
        addSpecialTokens: Boolean = true,
        maxLength: Int = 512
    ): TokenizerOutput {
        try {
            // Simple word-level tokenization
            val words = text.lowercase().split(Regex("\\s+"))

            val tokens = mutableListOf<Long>()

            // Add BOS token
            if (addSpecialTokens) {
                tokens.add(bosTokenId)
            }

            // Tokenize words
            for (word in words) {
                val tokenId = tokenToId[word] ?: unkTokenId
                tokens.add(tokenId)

                if (tokens.size >= maxLength - 1) break
            }

            // Add EOS token
            if (addSpecialTokens) {
                tokens.add(eosTokenId)
            }

            // Create attention mask (1 for real tokens, 0 for padding)
            val attentionMask = LongArray(tokens.size) { 1 }

            return TokenizerOutput(
                inputIds = tokens.toLongArray(),
                attentionMask = attentionMask
            )

        } catch (e: Exception) {
            Timber.e(e, "Tokenization failed")

            // Return minimal valid output
            return TokenizerOutput(
                inputIds = longArrayOf(bosTokenId, unkTokenId, eosTokenId),
                attentionMask = longArrayOf(1, 1, 1)
            )
        }
    }

    /**
     * Decode token IDs to text
     */
    fun decode(tokenIds: LongArray, skipSpecialTokens: Boolean = true): String {
        return try {
            val tokens = tokenIds.toList().mapNotNull { id ->
                val token = idToToken[id]

                // Skip special tokens if requested
                if (skipSpecialTokens && isSpecialToken(token)) {
                    null
                } else {
                    token
                }
            }

            tokens.joinToString(" ")

        } catch (e: Exception) {
            Timber.e(e, "Decoding failed")
            "[DECODE_ERROR]"
        }
    }

    /**
     * Check if token is special
     */
    private fun isSpecialToken(token: String?): Boolean {
        return token in setOf(bosToken, eosToken, unkToken, padToken)
    }

    /**
     * Load vocabulary file from assets
     */
    private fun loadVocabFile(): String {
        return try {
            context.assets.open(vocabPath).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Timber.w("Vocab file not found: $vocabPath")
            throw e
        }
    }

    /**
     * Get vocabulary size
     */
    fun getVocabSize(): Int = tokenToId.size

    /**
     * Check if tokenizer is ready
     */
    fun isInitialized(): Boolean = tokenToId.isNotEmpty()

    data class TokenizerOutput(
        val inputIds: LongArray,
        val attentionMask: LongArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TokenizerOutput

            if (!inputIds.contentEquals(other.inputIds)) return false
            if (!attentionMask.contentEquals(other.attentionMask)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = inputIds.contentHashCode()
            result = 31 * result + attentionMask.contentHashCode()
            return result
        }
    }
}
