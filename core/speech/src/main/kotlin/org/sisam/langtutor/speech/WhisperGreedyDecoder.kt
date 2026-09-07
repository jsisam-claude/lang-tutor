package org.sisam.langtutor.speech

import kotlin.math.exp

/**
 * Greedy decode loop for the two-signature Whisper tflite exports
 * (litert-community): the graph holds no KV cache, so every step re-runs the
 * decoder over the whole token prefix and we read logits at the last position.
 *
 * [stepLogits] runs the decoder: given the token buffer and how many entries
 * are valid, return the vocab logits for position `count - 1` — one float per
 * id in [layout]'s vocabulary, which differs between the multilingual and `.en`
 * exports. Pure JVM so the loop is unit-testable without a device; the Android
 * engine supplies a tflite-backed lambda.
 */
class WhisperGreedyDecoder(
    private val maxTokens: Int = MAX_TOKENS,
    /** Token layout of the loaded export — decides the prompt and EOT id. */
    private val layout: WhisperLayout = WhisperLayout.MULTILINGUAL,
    /**
     * Text the decoder is shown before it starts — Whisper's prompt, placed
     * after `<|startofprev|>` and before the layout's own prefix. The drill
     * passes the line it expects, so the decoder is biased towards writing
     * its words as the bank spells them. Cut to leave [RESERVE] positions
     * for the transcript itself; null or empty means no prompt.
     */
    prompt: IntArray? = null,
    private val stepLogits: (tokens: IntArray, count: Int) -> FloatArray,
) {

    /** Everything before the first decoded token. */
    private val prefix: IntArray = if (prompt == null || prompt.isEmpty()) {
        layout.prompt
    } else {
        val room = (maxTokens - RESERVE - layout.prompt.size - 1).coerceAtLeast(0)
        intArrayOf(layout.sotPrev) + prompt.copyOf(minOf(prompt.size, room)) + layout.prompt
    }

    /**
     * Content token ids plus the decode's own belief in them.
     *
     * [avgProb] is the mean softmax probability of each chosen token over the
     * distribution the loop actually samples from (content tokens + EOT).
     * This is what makes the orchestrator's "low confidence → ask the child to
     * repeat" path REAL: the engine used to hardcode 0.85, above the 0.5
     * policy threshold, so the repair branch was dead code. Whisper's greedy
     * token probability is a known-imperfect confidence signal, but garbage
     * decodes (repetition loops, wrong-script tokens) do sit visibly lower
     * than clean ones. DEVICE-VERIFY: calibrate the policy threshold against
     * real child recordings once the Pixel bench runs.
     */
    data class Decoded(val ids: IntArray, val avgProb: Float)

    fun transcribe(): Decoded {
        val tokens = IntArray(maxTokens)
        prefix.copyInto(tokens)
        var count = prefix.size
        var probSum = 0.0
        var probN = 0
        while (count < maxTokens) {
            val logits = stepLogits(tokens, count)
            val next = argmaxContent(logits)
            probSum += chosenProb(logits, next)
            probN++
            if (next == layout.eot) break
            tokens[count] = next
            count++
        }
        val avg = if (probN == 0) 0f else (probSum / probN).toFloat()
        return Decoded(tokens.copyOfRange(prefix.size, count), avg)
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

    /** Softmax probability of [chosen] over the allowed range [0, eot]. */
    private fun chosenProb(logits: FloatArray, chosen: Int): Double {
        var max = logits[layout.eot]
        for (i in 0 until layout.eot) if (logits[i] > max) max = logits[i]
        var sum = 0.0
        for (i in 0..layout.eot) sum += exp((logits[i] - max).toDouble())
        return exp((logits[chosen] - max).toDouble()) / sum
    }

    companion object {
        /** Multilingual prompt length. Prefer `layout.prompt.size` — the `.en`
         *  exports have a 2-token prompt. Kept for the decoder's own tests. */
        const val PREFIX = 4
        const val MAX_TOKENS = 128 // the export's decoder length

        /** Positions kept free for the transcript however long the prompt:
         *  ten seconds of speech is ~35 tokens. */
        const val RESERVE = 48
    }
}
