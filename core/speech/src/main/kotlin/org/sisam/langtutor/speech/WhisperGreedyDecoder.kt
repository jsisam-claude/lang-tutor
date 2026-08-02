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
    /** Token layout of the loaded export — decides the prompt and EOT id. */
    private val layout: WhisperLayout = WhisperLayout.MULTILINGUAL,
    private val stepLogits: (tokens: IntArray, count: Int) -> FloatArray,
) {

    /** @return content token ids (specials stripped), ready for the tokenizer. */
    fun transcribe(): IntArray {
        val tokens = IntArray(maxTokens)
        val prompt = layout.prompt
        prompt.copyInto(tokens)
        var count = prompt.size
        while (count < maxTokens) {
            val logits = stepLogits(tokens, count)
            val next = argmaxContent(logits)
            if (next == layout.eot) break
            tokens[count] = next
            count++
        }
        return tokens.copyOfRange(prompt.size, count)
    }

    /** Argmax over content tokens + EOT; other specials/timestamps are banned
     *  (minimal version of Whisper's suppress list). */
    private fun argmaxContent(logits: FloatArray): Int {
        var best = layout.eot
        var bestVal = logits[layout.eot]
        for (i in 0 until layout.eot) {
            if (logits[i] > bestVal) {
                bestVal = logits[i]
                best = i
            }
        }
        return best
    }

    companion object {
        /** Multilingual prompt length; kept for callers that still assume it. */
        const val PREFIX = 4
        const val MAX_TOKENS = 128 // the export's decoder length
    }
}
