package com.localai.assistant.data.local

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Phi-3 BPE Tokenizer
 * Implements byte-level BPE (Byte Pair Encoding) for Phi-3 mini model
 *
 * Vocab size: 32,064 tokens (32,000 base + 64 special tokens)
 * Based on tokenizer.json from microsoft/Phi-3-mini-4k-instruct
 */
class Phi3BPETokenizer(private val context: Context) {

    private var vocab: Map<String, Int> = emptyMap()
    private var vocabReverse: Map<Int, String> = emptyMap()
    private var merges: Map<Pair<String, String>, Int> = emptyMap()
    private var specialTokens: Map<String, Int> = emptyMap()

    // Special token IDs
    private val bosTokenId = 1  // <s>
    private val eosTokenId = 2  // </s>
    private val unkTokenId = 0  // <unk>

    fun initialize() {
        try {
            Timber.d("Loading Phi-3 BPE tokenizer...")

            // Load and parse tokenizer.json from assets
            val tokenizerJson = loadTokenizerJson()

            // Parse vocab
            vocab = parseVocab(tokenizerJson)
            vocabReverse = vocab.entries.associate { it.value to it.key }

            // Parse BPE merges
            merges = parseMerges(tokenizerJson)

            // Parse special tokens
            specialTokens = parseSpecialTokens(tokenizerJson)

            Timber.i("Phi-3 SentencePiece BPE tokenizer loaded: vocab=${vocab.size}, merges=${merges.size}, special=${specialTokens.size}")

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Phi-3 BPE tokenizer")
            throw e
        }
    }

    /**
     * Encode text to token IDs
     */
    fun encode(text: String, addSpecialTokens: Boolean = true, maxLength: Int = 4096): TokenizedInput {
        if (!isInitialized()) {
            throw IllegalStateException("Tokenizer not initialized")
        }

        val tokens = mutableListOf<Long>()

        // Add BOS token
        if (addSpecialTokens) {
            tokens.add(bosTokenId.toLong())
        }

        // Tokenize text
        val textTokens = tokenizeText(text)
        tokens.addAll(textTokens.map { it.toLong() })

        // Truncate if needed
        val finalTokens = if (tokens.size > maxLength) {
            tokens.subList(0, maxLength)
        } else {
            tokens
        }

        // Create attention mask (all 1s)
        val attentionMask = LongArray(finalTokens.size) { 1L }

        return TokenizedInput(
            inputIds = finalTokens.toLongArray(),
            attentionMask = attentionMask
        )
    }

    /**
     * Decode token IDs to text
     */
    fun decode(tokenIds: LongArray, skipSpecialTokens: Boolean = true): String {
        if (!isInitialized()) {
            throw IllegalStateException("Tokenizer not initialized")
        }

        val tokens = mutableListOf<String>()

        for (id in tokenIds) {
            val token = vocabReverse[id.toInt()]
            if (token != null) {
                // Skip special tokens if requested
                if (skipSpecialTokens && specialTokens.values.contains(id.toInt())) {
                    continue
                }
                tokens.add(token)
            } else {
                Timber.w("Unknown token ID: $id")
            }
        }

        // Decode byte-level BPE
        return decodeTokens(tokens)
    }

    /**
     * Tokenize text using SentencePiece BPE
     */
    private fun tokenizeText(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<Int>()

        // SentencePiece: replace spaces with ▁ (U+2581)
        val spaceMarker = "▁"
        val processedText = spaceMarker + text.replace(" ", spaceMarker)

        // Split into characters and apply BPE
        val bpeTokens = applyBPE(processedText)

        // Convert BPE tokens to IDs
        for (bpeToken in bpeTokens) {
            val tokenId = vocab[bpeToken] ?: unkTokenId
            tokens.add(tokenId)
        }

        return tokens
    }

    /**
     * Apply BPE merges to get subword tokens
     */
    private fun applyBPE(word: String): List<String> {
        if (word.length <= 1) return listOf(word)

        // Start with individual characters
        var pairs = word.map { it.toString() }.toMutableList()

        while (pairs.size > 1) {
            // Find the best merge (lowest rank)
            var bestPair: Pair<Int, Pair<String, String>>? = null

            for (i in 0 until pairs.size - 1) {
                val pair = Pair(pairs[i], pairs[i + 1])
                val rank = merges[pair]
                if (rank != null) {
                    if (bestPair == null || rank < bestPair.first) {
                        bestPair = Pair(rank, pair)
                    }
                }
            }

            // If no more merges, we're done
            if (bestPair == null) break

            // Apply the merge
            val (first, second) = bestPair.second
            val merged = first + second
            val newPairs = mutableListOf<String>()

            var i = 0
            while (i < pairs.size) {
                if (i < pairs.size - 1 && pairs[i] == first && pairs[i + 1] == second) {
                    newPairs.add(merged)
                    i += 2
                } else {
                    newPairs.add(pairs[i])
                    i++
                }
            }

            pairs = newPairs
        }

        return pairs
    }

    /**
     * Decode SentencePiece BPE tokens back to text
     */
    private fun decodeTokens(tokens: List<String>): String {
        // Join tokens
        val joined = tokens.joinToString("")

        // Replace SentencePiece space marker with actual spaces
        return joined.replace("▁", " ").trim()
    }

    /**
     * Load tokenizer.json from assets
     */
    private fun loadTokenizerJson(): JSONObject {
        val inputStream = context.assets.open("tokenizer.json")
        val reader = BufferedReader(InputStreamReader(inputStream))
        val jsonString = reader.use { it.readText() }
        return JSONObject(jsonString)
    }

    /**
     * Parse vocab from tokenizer.json
     */
    private fun parseVocab(tokenizerJson: JSONObject): Map<String, Int> {
        val vocabJson = tokenizerJson.getJSONObject("model").getJSONObject("vocab")
        val vocab = mutableMapOf<String, Int>()

        val keys = vocabJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            vocab[key] = vocabJson.getInt(key)
        }

        return vocab
    }

    /**
     * Parse BPE merges from tokenizer.json
     */
    private fun parseMerges(tokenizerJson: JSONObject): Map<Pair<String, String>, Int> {
        val mergesArray = tokenizerJson.getJSONObject("model").getJSONArray("merges")
        val merges = mutableMapOf<Pair<String, String>, Int>()

        for (i in 0 until mergesArray.length()) {
            val mergeArray = mergesArray.getJSONArray(i)
            if (mergeArray.length() == 2) {
                val first = mergeArray.getString(0)
                val second = mergeArray.getString(1)
                merges[Pair(first, second)] = i
            }
        }

        return merges
    }

    /**
     * Parse special tokens from tokenizer.json
     */
    private fun parseSpecialTokens(tokenizerJson: JSONObject): Map<String, Int> {
        val addedTokensArray = tokenizerJson.getJSONArray("added_tokens")
        val specialTokens = mutableMapOf<String, Int>()

        for (i in 0 until addedTokensArray.length()) {
            val token = addedTokensArray.getJSONObject(i)
            val content = token.getString("content")
            val id = token.getInt("id")
            val isSpecial = token.getBoolean("special")

            if (isSpecial) {
                specialTokens[content] = id
            }
        }

        return specialTokens
    }

    fun isInitialized(): Boolean = vocab.isNotEmpty()

    fun getVocabSize(): Int = vocab.size + specialTokens.size

    data class TokenizedInput(
        val inputIds: LongArray,
        val attentionMask: LongArray
    )
}
