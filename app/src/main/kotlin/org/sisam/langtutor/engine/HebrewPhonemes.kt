package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import org.sisam.langtutor.speech.DictaTokenizer
import org.sisam.langtutor.speech.KokoroFrontEnd
import org.sisam.langtutor.speech.KokoroPhonemizer
import org.sisam.langtutor.speech.NikudRestorer
import org.sisam.langtutor.speech.PhonikudPhonemizer

/**
 * Hebrew text → Kokoro phoneme ids: the Phonikud front end, fully on-device.
 *
 * Unpointed Hebrew → nikud model (char BERT, int8 ONNX, adds niqqud plus
 * stress/vocal-shva/prefix marks) → [PhonikudPhonemizer] rules (Kotlin port,
 * golden-pinned to the reference) → the SAME encoder the English voice uses.
 *
 * That last step is the whole reason this class is short. Kokoro's vocabulary
 * is 114 IPA symbols and the Hebrew export ships a byte-identical one, while
 * Phonikud already normalises its output to exactly those symbols (ɡ, χ and ʁ
 * rather than ASCII g, x, r) — verified over the reference corpus by
 * `HebrewKokoroVocabTest`. So there is no Hebrew tokenizer, no second symbol
 * table, and no mapping layer: only a different way of getting to IPA.
 *
 * The nikud model is the expensive half (~308 MB), so it is loaded lazily and
 * given back under memory pressure, exactly as the voices are.
 */
class HebrewPhonemes(private val nikudModel: File) : KokoroFrontEnd {

    private val tokenizer by lazy { DictaTokenizer.load() }
    private val encoder by lazy { KokoroPhonemizer.load() }

    @Volatile private var nikudSession: OrtSession? = null

    override fun phonemize(text: String): IntArray {
        val ipa = toIpa(text)
        return if (ipa.isBlank()) IntArray(0) else encoder.encode(ipa)
    }

    /** Pointed-Hebrew IPA for [text]; blank when there is nothing to say. */
    fun toIpa(text: String): String {
        if (text.isBlank()) return ""
        // The nikud model's context is 2046 tokens; sentence chunks are far
        // shorter, but a pathological unbroken line must not crash the turn.
        val bare = NikudRestorer.removeNikud(text).take(NIKUD_MAX_CHARS)
        if (bare.isBlank()) return ""
        return PhonikudPhonemizer.phonemize(addNikud(bare))
    }

    /** Force the lazy pieces now (background call) so the first line is instant. */
    fun warmUp() {
        session()
        tokenizer
        encoder
    }

    /** Frees the nikud session under memory pressure; next use reloads lazily. */
    fun release() = synchronized(this) {
        nikudSession?.let {
            runCatching { it.close() }
            nikudSession = null
            Log.i(TAG, "nikud session released (memory pressure)")
        }
        Unit
    }

    private fun session(): OrtSession = nikudSession ?: synchronized(this) {
        nikudSession ?: createSession().also { nikudSession = it }
    }

    // ~308 MB, so the first Hebrew line waits on a real load — reported by
    // name rather than left as silence.
    private fun createSession(): OrtSession =
        EngineStatus.step(EngineStatus.Kind.HEBREW_LOAD, nikudModel.name) {
            val started = System.nanoTime()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                // Same conservative level as the Kokoro engine (see its note).
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            OrtEnvironment.getEnvironment().createSession(nikudModel.absolutePath, opts).also {
                Log.i(TAG, "nikud session loaded in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

    /** Run the nikud model on bare text; returns pointed text with marks. */
    @Synchronized
    private fun addNikud(text: String): String {
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
            session().run(inputs).use { results ->
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

    private companion object {
        const val TAG = "TukiTtsHe"
        const val THREADS = 4
        const val NIKUD_MAX_CHARS = 2000
    }
}
