package org.sisam.langtutor.speech

/**
 * Greedy decode loop for the two-signature Whisper tflite exports
 * (litert-community): the graph holds no KV cache, so every step re-runs the
 * decoder over the whole token prefix and we read logits at the last position.
 *
 * [stepLogits] runs the decoder: given the token buffer and how many entries
 * are valid, return the vocab logits (size 51_865) for position `count - 1`.
 * Pure JVM so the loop is unit-testable without a device; the Android engine
 * supplies a tflite-backed lambda.
 */
class WhisperGreedyDecoder(
    private val maxTokens: Int = MAX_TOKENS,
    private val stepLogits: (tokens: IntArray, count: Int) -> FloatArray,
) {

    /** @return content token ids (specials stripped), ready for the tokenizer. */
    fun transcribe(): IntArray {
        val tokens = IntArray(maxTokens)
        tokens[0] = WhisperTokenizer.SOT
        tokens[1] = WhisperTokenizer.LANG_EN
        tokens[2] = WhisperTokenizer.TRANSCRIBE
        tokens[3] = WhisperTokenizer.NO_TIMESTAMPS
        var count = PREFIX
        while (count < maxTokens) {
            val logits = stepLogits(tokens, count)
            val next = argmaxContent(logits)
            if (next == WhisperTokenizer.EOT) break
            tokens[count] = next
            count++
        }
        return tokens.copyOfRange(PREFIX, count)
    }

    /** Argmax over content tokens + EOT; other specials/timestamps are banned
     *  (minimal version of Whisper's suppress list). */
    private fun argmaxContent(logits: FloatArray): Int {
        var best = WhisperTokenizer.EOT
        var bestVal = logits[WhisperTokenizer.EOT]
        for (i in 0 until WhisperTokenizer.EOT) {
            if (logits[i] > bestVal) {
                bestVal = logits[i]
                best = i
            }
        }
        return best
    }

    companion object {
        const val PREFIX = 4
        const val MAX_TOKENS = 128 // the export's decoder length
    }
}
