package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sisam.langtutor.speech.AudioClip
import org.sisam.langtutor.speech.EspeakPhonemes
import org.sisam.langtutor.speech.GopScorer
import org.sisam.langtutor.speech.KokoroPhonemizer
import org.sisam.langtutor.speech.PhonemeScore
import org.sisam.langtutor.speech.PronunciationScore
import org.sisam.langtutor.speech.PronunciationScorer
import org.sisam.langtutor.speech.TutorLanguage

/**
 * REAL pronunciation scoring, fully on-device — the in-house answer to there
 * being no offline pronunciation SDK anywhere (docs/feasibility.md §5).
 *
 * wav2vec2-lv-60-espeak (int8 ONNX, 318 MB, Apache-2.0) turns the child's
 * recording into per-frame IPA phoneme posteriors; [GopScorer] force-aligns
 * those against the phonemes the lesson word SHOULD have, and reports how
 * confidently the model heard each expected sound. The expected phonemes come
 * from the same [KokoroPhonemizer] that drives the tutor's own voice, so the
 * model and the lesson always agree on what "red" is made of.
 *
 * Validated in-container on the Hebrew-L1 substitutions from the docs: correct
 * sounds scored 0.00, r→w / θ→s / ð→d / v→w / p→b scored −4.9 to −7.4.
 * DEVICE-VERIFY: those numbers come from clean synthesized speech; thresholds
 * ([GopScorer.Thresholds]) likely need a pass on real children in real rooms.
 */
class Wav2Vec2GopEngine(private val modelFile: File) : PronunciationScorer {

    private val phonemizer by lazy { KokoroPhonemizer.load() }

    // Nullable + accessor rather than `by lazy`, so trimMemory() can free the
    // ~320 MB coach and the next scored attempt quietly reloads it.
    @Volatile private var session: OrtSession? = null

    private fun session(): OrtSession = session ?: synchronized(this) {
        session ?: createSession().also { session = it }
    }

    /** Frees the ORT session under memory pressure; next use reloads lazily. */
    fun release() = synchronized(this) {
        session?.let {
            runCatching { it.close() }
            session = null
            Log.i(TAG, "session released (memory pressure)")
        }
    }

    private fun createSession(): OrtSession =
        EngineStatus.step(EngineStatus.Kind.COACH_LOAD, modelFile.name) {
            val started = System.nanoTime()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, opts).also {
                Log.i(TAG, "gop session loaded in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

    override suspend fun score(
        audio: AudioClip,
        expectedText: String,
        language: TutorLanguage,
    ): PronunciationScore = withContext(Dispatchers.Default) {
        // English only for now: the model is multilingual but our expected-phoneme
        // path (CMU + misaki) is English. Hebrew turns simply aren't scored.
        if (language != TutorLanguage.ENGLISH) return@withContext EMPTY

        val expected = EspeakPhonemes.expectedFrom(
            phonemizer.phonemizeToIpa(expectedText),
        )
        if (expected.isEmpty() || audio.samples.size < MIN_SAMPLES) return@withContext EMPTY

        val started = System.nanoTime()
        val logProbs = EngineStatus.step(EngineStatus.Kind.COACH_RUN, "${audio.durationMs}ms audio") {
            runCatching { posteriors(audio) }
                .onFailure { Log.e(TAG, "scoring failed", it) }
                .getOrNull()
        } ?: return@withContext EMPTY

        val scored = GopScorer.score(
            logProbs = logProbs,
            targetIds = expected.map { it.id }.toIntArray(),
            targetLabels = expected.map { it.label },
            blankId = EspeakPhonemes.blankId,
        )
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(
            TAG,
            "scored '$expectedText' (${audio.durationMs}ms audio, ${logProbs.size} frames) in ${ms}ms: " +
                scored.joinToString(" ") { "${it.phoneme}=${"%.1f".format(it.gop)}" },
        )
        PronunciationScore(
            overall = GopScorer.overall(scored),
            phonemes = scored.map { PhonemeScore(it.phoneme, verdictToScore(it.verdict)) },
        )
    }

    /** Log-softmax posteriors [frames][vocab] for the recording. */
    // @Synchronized so release() waits for the in-flight scoring pass.
    @Synchronized
    private fun posteriors(audio: AudioClip): Array<FloatArray> {
        val n = audio.samples.size
        // Wav2Vec2FeatureExtractor: raw 16 kHz float, zero-mean unit-variance.
        val x = FloatArray(n)
        var mean = 0.0
        for (i in 0 until n) {
            x[i] = audio.samples[i] / 32768f
            mean += x[i]
        }
        mean /= n
        var variance = 0.0
        for (v in x) variance += (v - mean) * (v - mean)
        val scale = 1.0 / kotlin.math.sqrt(variance / n + 1e-7)
        for (i in 0 until n) x[i] = ((x[i] - mean) * scale).toFloat()

        val env = OrtEnvironment.getEnvironment()
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, n.toLong()))
        try {
            session().run(mapOf("input_values" to input)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val logits = (results[0] as OnnxTensor).value as Array<Array<FloatArray>>
                return GopScorer.logSoftmax(logits[0])
            }
        } finally {
            runCatching { input.close() }
        }
    }

    /** UI-facing 0..1 per sound: green ≈ 1, amber ≈ 0.55, red ≈ 0.15. */
    private fun verdictToScore(v: GopScorer.Verdict): Float = when (v) {
        GopScorer.Verdict.GOOD -> 1.0f
        GopScorer.Verdict.CLOSE -> 0.55f
        GopScorer.Verdict.WRONG -> 0.15f
    }

    fun warmUp() {
        session()
        phonemizer
    }

    private companion object {
        const val TAG = "TukiGop"
        const val THREADS = 4
        const val MIN_SAMPLES = 16_000 / 4 // shorter than 0.25 s isn't a word
        val EMPTY = PronunciationScore(overall = 0f, phonemes = emptyList())
    }
}
