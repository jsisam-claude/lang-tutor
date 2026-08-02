package org.sisam.langtutor.speech

import kotlin.math.exp
import kotlin.math.ln

/**
 * Goodness-of-Pronunciation scoring over a phoneme-CTC model's output — the
 * in-house replacement for the cloud pronunciation SDKs that don't exist
 * offline (docs/feasibility.md §5).
 *
 * Given per-frame log-probabilities over the phoneme vocabulary and the
 * phonemes the child was SUPPOSED to say:
 *  1. CTC forced alignment (Viterbi over the blank-extended target) pins each
 *     expected phoneme to the frames where the model thinks it occurred;
 *  2. per phoneme, GOP = mean over its frames of
 *        log p(expected phone) − log p(most likely phone)
 *     which is 0 when the model agrees with the target and grows negative the
 *     more it prefers something else.
 *
 * Calibrated in-container against the Hebrew-L1 substitutions from the docs
 * (r→w, θ→s, ð→d, v→w, p→b): correct pronunciations scored 0.00, substituted
 * ones −4.9 to −7.4. Thresholds sit in that gap with generous slack.
 *
 * HONEST LIMITS: (a) that calibration used clean synthesized speech —
 * real children in real rooms will score lower on correct sounds, so
 * [Thresholds] is expected to need a pass on device audio; (b) vowel-quality
 * errors (æ vs ɛ) did NOT separate in testing, so vowels are reported but
 * should be treated as advisory; (c) CTC is peaky, so a phoneme may own only a
 * frame or two — few frames, but empirically decisive.
 */
object GopScorer {

    /** Score bands. See the calibration note above before changing these. */
    data class Thresholds(val good: Float = -1.0f, val close: Float = -3.0f)

    data class Scored(
        val phoneme: String,
        /** ≤ 0; 0 means the model fully agreed with the expected phoneme. */
        val gop: Float,
        val frames: Int,
        val verdict: Verdict,
    )

    enum class Verdict { GOOD, CLOSE, WRONG }

    private const val NEG_INF = -1e30

    /**
     * @param logProbs [frames][vocab] log-softmax output of the phoneme CTC model
     * @param targetIds vocabulary ids of the expected phonemes, in order
     * @param targetLabels display labels for [targetIds] (same length)
     * @param blankId CTC blank id (the model's `<pad>`)
     */
    fun score(
        logProbs: Array<FloatArray>,
        targetIds: IntArray,
        targetLabels: List<String>,
        blankId: Int,
        thresholds: Thresholds = Thresholds(),
    ): List<Scored> {
        require(targetIds.size == targetLabels.size) { "labels must match ids" }
        if (logProbs.isEmpty() || targetIds.isEmpty()) return emptyList()

        val spans = forcedAlign(logProbs, targetIds, blankId)
        val frameMax = FloatArray(logProbs.size) { t -> logProbs[t].max() }

        return targetIds.indices.map { i ->
            val frames = spans[i]
            if (frames.isEmpty()) {
                // Never aligned anywhere: the sound simply wasn't produced.
                Scored(targetLabels[i], NOT_ALIGNED, 0, Verdict.WRONG)
            } else {
                var sum = 0.0
                for (t in frames) sum += (logProbs[t][targetIds[i]] - frameMax[t]).toDouble()
                val gop = (sum / frames.size).toFloat()
                Scored(
                    phoneme = targetLabels[i],
                    gop = gop,
                    frames = frames.size,
                    verdict = when {
                        gop >= thresholds.good -> Verdict.GOOD
                        gop >= thresholds.close -> Verdict.CLOSE
                        else -> Verdict.WRONG
                    },
                )
            }
        }
    }

    /** Overall 0..1 score for the utterance — the child-facing number. */
    fun overall(scores: List<Scored>): Float {
        if (scores.isEmpty()) return 0f
        // Map each GOP through a soft curve: 0 → 1.0, −3 → ~0.5, −7 → ~0.1.
        val mean = scores.map { 1f / (1f + exp(-(it.gop + 2.2f))) }.average().toFloat()
        return mean.coerceIn(0f, 1f)
    }

    /** Frames assigned to each target symbol by CTC Viterbi forced alignment. */
    internal fun forcedAlign(
        logProbs: Array<FloatArray>,
        targetIds: IntArray,
        blankId: Int,
    ): List<List<Int>> {
        val t = logProbs.size
        // Blank-extended target: blank, s0, blank, s1, blank, …
        val ext = IntArray(targetIds.size * 2 + 1) { i ->
            if (i % 2 == 0) blankId else targetIds[i / 2]
        }
        val s = ext.size
        val dp = Array(t) { DoubleArray(s) { NEG_INF } }
        val back = Array(t) { IntArray(s) }

        dp[0][0] = logProbs[0][ext[0]].toDouble()
        if (s > 1) dp[0][1] = logProbs[0][ext[1]].toDouble()

        for (frame in 1 until t) {
            for (state in 0 until s) {
                var best = dp[frame - 1][state]
                var arg = state
                if (state > 0 && dp[frame - 1][state - 1] > best) {
                    best = dp[frame - 1][state - 1]
                    arg = state - 1
                }
                // A blank may be skipped only between two DIFFERENT labels.
                if (state > 1 && ext[state] != blankId && ext[state] != ext[state - 2] &&
                    dp[frame - 1][state - 2] > best
                ) {
                    best = dp[frame - 1][state - 2]
                    arg = state - 2
                }
                dp[frame][state] = if (best <= NEG_INF) NEG_INF else best + logProbs[frame][ext[state]]
                back[frame][state] = arg
            }
        }

        var state = if (s == 1 || dp[t - 1][s - 1] >= dp[t - 1][s - 2]) s - 1 else s - 2
        val path = IntArray(t)
        for (frame in t - 1 downTo 0) {
            path[frame] = state
            state = back[frame][state]
        }

        val spans = List(targetIds.size) { mutableListOf<Int>() }
        for (frame in 0 until t) {
            val st = path[frame]
            if (ext[st] != blankId) spans[(st - 1) / 2].add(frame)
        }
        return spans
    }

    /** Turns raw logits into log-softmax rows, in place-friendly fashion. */
    fun logSoftmax(logits: Array<FloatArray>): Array<FloatArray> = Array(logits.size) { t ->
        val row = logits[t]
        val max = row.max()
        var sum = 0.0
        for (v in row) sum += exp((v - max).toDouble())
        val logSum = ln(sum) + max
        FloatArray(row.size) { row[it] - logSum.toFloat() }
    }

    /** Score for a phoneme the model never aligned — far below any threshold. */
    const val NOT_ALIGNED = -99f
}
