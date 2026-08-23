package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.sisam.langtutor.speech.DictaTokenizer
import org.sisam.langtutor.speech.NikudRestorer
import org.sisam.langtutor.speech.PhonikudPhonemizer
import org.sisam.langtutor.speech.PiperPhonemes
import org.sisam.langtutor.speech.SentenceChunker
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/**
 * Bundled HEBREW voice — the Phonikud stack, fully on-device: unpointed text
 * → nikud model (char BERT, int8 ONNX, adds niqqud + stress/shva/prefix
 * marks) → [PhonikudPhonemizer] rules (Kotlin port, golden-pinned to the
 * reference) → Piper VITS voice (raw phonemes, NO espeak) → 22.05 kHz PCM.
 * Validated end-to-end in-container at RTF ≈ 0.09 on one CPU thread, so a
 * Pixel speaks Hebrew comfortably in realtime.
 *
 * [AppContainer] wires this behind [TtsRouter]; it only exists when BOTH
 * model files are installed. There is NO pack for them any more: the voice
 * checkpoint's CC-BY-NC license blocks distribution (docs/feasibility.md), so
 * this engine only activates in development, via privately pushed files.
 */
class HebrewTtsEngine(
    private val nikudModel: File,
    private val voiceModel: File,
) : TtsEngine {

    private val tokenizer by lazy { DictaTokenizer.load() }
    private val player = PcmPlayer(SAMPLE_RATE)

    // Nullable + accessors rather than `by lazy`, so trimMemory() can free the
    // ~0.45 GB Hebrew stack and the next Hebrew line quietly reloads it.
    @Volatile private var nikudSession: OrtSession? = null
    @Volatile private var voiceSession: OrtSession? = null

    private fun nikudSession(): OrtSession = nikudSession ?: synchronized(this) {
        nikudSession ?: createSession(nikudModel, "nikud").also { nikudSession = it }
    }

    private fun voiceSession(): OrtSession = voiceSession ?: synchronized(this) {
        voiceSession ?: createSession(voiceModel, "voice").also { voiceSession = it }
    }

    /** Frees both ORT sessions under memory pressure; next use reloads lazily. */
    fun release() = synchronized(this) {
        if (nikudSession != null || voiceSession != null) {
            runCatching { nikudSession?.close() }
            runCatching { voiceSession?.close() }
            nikudSession = null
            voiceSession = null
            Log.i(TAG, "sessions released (memory pressure)")
        }
    }

    // The nikud model alone is ~308 MB, so the first Hebrew line waits on a
    // real load — reported by name rather than left as silence.
    private fun createSession(model: File, label: String): OrtSession =
        EngineStatus.step(EngineStatus.Kind.HEBREW_LOAD, "$label (${model.name})") {
            val started = System.nanoTime()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                // Same conservative level as the Kokoro engine (see its note).
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            OrtEnvironment.getEnvironment().createSession(model.absolutePath, opts).also {
                Log.i(TAG, "$label session loaded in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> = flow {
        player.interrupted = false
        emit(TtsEvent.Started)
        for (chunk in SentenceChunker.split(text)) {
            if (player.interrupted) break
            val audio = EngineStatus.step(EngineStatus.Kind.HEBREW_RUN, "${chunk.text.length} chars") {
                synthesize(chunk.text, speed)
            }
            if (audio.isEmpty()) continue
            emit(TtsEvent.RangeSpoken(chunk.start, chunk.end))
            if (!player.interrupted) player.play(audio)
        }
        player.release()
        emit(TtsEvent.Completed)
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        player.interrupted = true
        player.release()
    }

    /** Force the lazy pieces now (background call) so first speak is instant. */
    fun warmUp() {
        nikudSession()
        voiceSession()
        tokenizer
    }

    // @Synchronized so release() waits for the in-flight sentence.
    @Synchronized
    private fun synthesize(text: String, speed: Float): FloatArray {
        val started = System.nanoTime()
        // The nikud model's context is 2046 tokens; sentence chunks are far
        // shorter, but a pathological unbroken line must not crash the turn.
        val pointed = addNikud(NikudRestorer.removeNikud(text).take(NIKUD_MAX_CHARS))
        val phonemes = PhonikudPhonemizer.phonemize(pointed)
        if (phonemes.isBlank()) return FloatArray(0)
        val ids = PiperPhonemes.toIds(phonemes)

        val env = OrtEnvironment.getEnvironment()
        // Piper length_scale is inverse speed: 1.0 normal, >1 slower. The
        // engine contract's speed<1 = slow-clear mode maps accordingly.
        val lengthScale = if (speed > 0f) 1f / speed else 1f
        val inputs = mapOf(
            "input" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
            "input_lengths" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)),
            "scales" to OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArrayOf(NOISE_SCALE, lengthScale, NOISE_W)),
                longArrayOf(3),
            ),
        )
        try {
            voiceSession().run(inputs).use { results ->
                val buf = (results[0] as OnnxTensor).floatBuffer
                val audio = FloatArray(buf.remaining())
                buf.get(audio)
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(
                    TAG,
                    "synth ${phonemes.length} phonemes -> " +
                        "${"%.2f".format(audio.size / SAMPLE_RATE.toFloat())}s in ${ms}ms",
                )
                return audio
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /** Run the nikud model on bare text; returns pointed text with marks. */
    private fun addNikud(text: String): String {
        if (text.isBlank()) return text
        val ids = tokenizer.encode(text)
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, ids.size.toLong())
        val ones = LongArray(ids.size) { 1L }
        val zeros = LongArray(ids.size)
        val inputs = mapOf(
            "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape),
            "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(ones), shape),
            "token_type_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(zeros), shape),
        )
        try {
            nikudSession().run(inputs).use { results ->
                // Outputs: nikud_logits [1,T,29], shin_logits [1,T,2], additional_logits [1,T,3]
                @Suppress("UNCHECKED_CAST")
                val nikud = (results.get("nikud_logits").get() as OnnxTensor).value as Array<Array<FloatArray>>
                @Suppress("UNCHECKED_CAST")
                val shin = (results.get("shin_logits").get() as OnnxTensor).value as Array<Array<FloatArray>>
                @Suppress("UNCHECKED_CAST")
                val add = (results.get("additional_logits").get() as OnnxTensor).value as Array<Array<FloatArray>>
                val n = text.length
                // Token i+1 belongs to char i ([CLS] shift) — pinned by the
                // restorer fixture test against the real model.
                return NikudRestorer.restore(
                    text = text,
                    nikudClass = IntArray(n) { argmax(nikud[0][it + 1]) },
                    shinClass = IntArray(n) { argmax(shin[0][it + 1]) },
                    stress = BooleanArray(n) { add[0][it + 1][0] > 0f },
                    vocalShva = BooleanArray(n) { add[0][it + 1][1] > 0f },
                    prefix = BooleanArray(n) { add[0][it + 1][2] > 0f },
                )
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        for (i in 1 until row.size) if (row[i] > row[best]) best = i
        return best
    }

    companion object {
        private const val TAG = "TukiTtsHe"
        private const val SAMPLE_RATE = 22_050
        private const val THREADS = 4
        private const val NIKUD_MAX_CHARS = 2000

        // Piper inference defaults of the bundled voice (model.config.json).
        private const val NOISE_SCALE = 0.667f
        private const val NOISE_W = 0.8f
    }
}
