package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.sisam.langtutor.speech.KaldiFbank

/**
 * Streaming ASR — the k2 streaming Zipformer transducer, decoding WHILE the
 * learner is still speaking (docs/latency.md).
 *
 * Whisper re-decodes a whole window every time it is asked, which is why the
 * live "heard so far" preview costs a full decode per guess. This model was
 * built for the other shape: it carries its own state across 320 ms chunks,
 * so each chunk costs one small forward pass and the transcript simply grows.
 *
 * ## Nothing here is hardcoded from documentation
 *
 * The consultation that recommended this model got its central facts wrong —
 * it said 7-9 state tensors and a 160 ms stride; the graph says **98 states**
 * and, via `T=45` / `decode_chunk_len=32`, a **320 ms** stride. So this class
 * asks the SESSION for everything: the state tensors are allocated from the
 * encoder's own declared input shapes, and a state is paired with its updated
 * value by name (`cached_key_0` <- `new_cached_key_0`). If a future export
 * changes its state layout, this code adapts instead of silently feeding the
 * encoder zeros.
 *
 * ## Division of labour
 *
 * This engine feeds the PREVIEW and the endpoint; Whisper still produces the
 * judged transcript until this model's accuracy on child and Hebrew-accented
 * speech has been measured on real devices. That is also why greedy decoding
 * is enough here: contextual biasing (the drill's expected answer) needs beam
 * search plus a token trie, and the drill is judged by Whisper anyway.
 */
class ZipformerStreamingAsr(
    context: Context,
    private val installStamp: String = "",
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val fbank = KaldiFbank()

    internal data class Models(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val joiner: OrtSession,
        val tokens: List<String>,
        /** Encoder input name -> shape with the batch dim resolved to 1. */
        val stateShapes: Map<String, LongArray>,
        /** Encoder frames per forward pass (metadata `T`). */
        val windowFrames: Int,
        /** Frames the window advances per pass (metadata `decode_chunk_len`). */
        val strideFrames: Int,
        val contextSize: Int,
    )

    @Volatile private var models: Models? = null

    /**
     * Session lifetime versus in-flight use. [close] must never pull the
     * three sessions out from under a [Stream] that is inside `encoder.run`
     * on the capture thread: ORT keeps no in-flight-run count, so that is a
     * native use-after-free — and a foreground-critical memory trim delivers
     * close() at exactly that moment. Chunks run under the read side; close()
     * takes the write side, so it waits for at most one small forward pass,
     * after which no chunk can start and a surviving stream fails cleanly.
     */
    private val sessionLock = ReentrantReadWriteLock()

    /** True when the weights are present; absent assets simply disable the feature. */
    val available: Boolean by lazy {
        runCatching { appContext.assets.list(ASSET_DIR)?.contains(ENCODER) == true }.getOrDefault(false)
    }

    private fun load(): Models = synchronized(this) {
        models?.let { return it }
        return EngineStatus.step(EngineStatus.Kind.ASR_LOAD, ENCODER) {
            val started = System.nanoTime()
            // Three sessions, ~73 MB between them, and ORT reclaims none of
            // them on its own. So from the first build to `models = m`, ANY
            // throw — a later session build, the tokens read, a metadata
            // call, the shape check — hands every session built so far back.
            val enc = OnnxTuning.createSession(asset(ENCODER).absolutePath, "$TAG.enc", installStamp)
            var dec: OrtSession? = null
            var join: OrtSession? = null
            try {
                val d = OnnxTuning.createSession(asset(DECODER).absolutePath, "$TAG.dec", installStamp, threads = 1)
                dec = d
                val j = OnnxTuning.createSession(asset(JOINER).absolutePath, "$TAG.join", installStamp, threads = 1)
                join = j
                val tokens = appContext.assets.open("$ASSET_DIR/$TOKENS").bufferedReader().useLines { lines ->
                    // "<piece> <id>" per line, ids ascending from 0.
                    lines.mapNotNull { it.substringBeforeLast(' ').takeIf { p -> p.isNotEmpty() } }.toList()
                }
                val meta = enc.metadata.customMetadata
                val shapes = enc.inputInfo.entries
                    .filter { it.key != INPUT_AUDIO }
                    .associate { (name, info) ->
                        val dims = (info.info as TensorInfo).shape
                        // The only dynamic dim in this export is the batch; a
                        // negative anywhere else would mean the graph changed
                        // shape in a way this loop cannot guess, so it is loud.
                        name to LongArray(dims.size) { i ->
                            dims[i].also { dim -> require(dim > 0 || i == batchDim(dims)) { "$name dim $i is dynamic" } }
                                .let { dim -> if (dim > 0) dim else 1L }
                        }
                    }
                val m = Models(
                    encoder = enc, decoder = d, joiner = j, tokens = tokens,
                    stateShapes = shapes,
                    windowFrames = meta["T"]?.toIntOrNull() ?: DEFAULT_WINDOW,
                    strideFrames = meta["decode_chunk_len"]?.toIntOrNull() ?: DEFAULT_STRIDE,
                    contextSize = d.metadata.customMetadata["context_size"]?.toIntOrNull() ?: 2,
                )
                Log.i(
                    TAG,
                    "loaded in ${(System.nanoTime() - started) / 1_000_000}ms: ${tokens.size} tokens, " +
                        "${shapes.size} states, window=${m.windowFrames} stride=${m.strideFrames} " +
                        "(${m.strideFrames * fbank.frameShift * 1000 / SAMPLE_RATE}ms/chunk)",
                )
                models = m
                m
            } catch (t: Throwable) {
                runCatching { join?.close() }
                runCatching { dec?.close() }
                runCatching { enc.close() }
                throw t
            }
        }
    }

    /** Index of the batch dimension — the one the export leaves dynamic. */
    private fun batchDim(dims: LongArray): Int = dims.indexOfFirst { it <= 0 }

    private fun asset(name: String): File {
        val out = File(appContext.filesDir, "$ASSET_DIR/$name")
        if (out.isFile && out.length() > 0) return out
        out.parentFile?.mkdirs()
        appContext.assets.open("$ASSET_DIR/$name").use { input ->
            File(out.parentFile, "${out.name}.part").let { tmp ->
                tmp.outputStream().use(input::copyTo)
                check(tmp.renameTo(out)) { "could not place $name" }
            }
        }
        return out
    }

    /**
     * One utterance in progress. Not thread-safe by design: it belongs to the
     * single ASR worker thread, and the audio callback must never touch it.
     */
    inner class Stream internal constructor(private val m: Models) {
        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        private val states = HashMap<String, OnnxTensor>()
        private var samples = FloatArray(0)
        private var frames = ArrayList<FloatArray>()
        private var nextFrame = 0
        private var encStart = 0
        private val hypothesis = ArrayList<Int>()
        private var decoderOut: FloatArray? = null
        // Its own front end, not the engine's: the FFT plan inside is per
        // instance, so two streams never share mutable state whatever their
        // threads are doing.
        private val fbank = KaldiFbank()

        init { resetStates() }

        private fun resetStates() {
            states.values.forEach { runCatching { it.close() } }
            states.clear()
            for ((name, shape) in m.stateShapes) {
                val n = shape.fold(1L) { a, b -> a * b }.toInt()
                states[name] = if (name == PROCESSED_LENS) {
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), shape)
                } else {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n)), shape)
                }
            }
        }

        /**
         * Feed newly captured audio. Returns the transcript so far when it
         * grew, null when nothing new was decoded — so a caller can emit only
         * on change.
         */
        fun accept(pcm: FloatArray): String? {
            samples = samples.plus(pcm)
            var grew = false
            // One fbank call over the whole newly-available span, not one per
            // 10 ms frame: compute() batches, and a per-frame call repeated
            // the window/mel setup for every frame on the audio path.
            val spanStart = nextFrame * fbank.frameShift
            val available = samples.size - spanStart
            val ready = fbank.frameCount(available)
            if (ready > 0) {
                val end = spanStart + (ready - 1) * fbank.frameShift + fbank.frameLength
                frames.addAll(fbank.compute(samples.copyOfRange(spanStart, end)))
                nextFrame += ready
            }
            while (frames.size - encStart >= m.windowFrames) {
                if (runChunk(encStart)) grew = true
                encStart += m.strideFrames
            }
            return if (grew) text() else null
        }

        /** Flush what is left: the tail is padded so a final partial window still decodes. */
        fun finish(): String {
            if (frames.size > encStart) {
                val pad = ArrayList(frames.subList(encStart, frames.size))
                while (pad.size < m.windowFrames) pad.add(FloatArray(FEATURE_DIM) { SILENCE_LOGMEL })
                runChunk(0, pad)
            }
            return text()
        }

        private fun runChunk(from: Int, explicit: List<FloatArray>? = null): Boolean = sessionLock.read {
            // Closed under us (a trim): fail here, before touching a session;
            // the capture loop turns the throw into "preview off".
            check(models === m) { "streaming engine closed under a live stream" }
            val window = explicit ?: frames.subList(from, from + m.windowFrames)
            val flat = FloatArray(m.windowFrames * FEATURE_DIM)
            for (f in 0 until m.windowFrames) {
                System.arraycopy(window[f], 0, flat, f * FEATURE_DIM, FEATURE_DIM)
            }
            val x = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flat), longArrayOf(1, m.windowFrames.toLong(), FEATURE_DIM.toLong()),
            )
            val inputs = HashMap<String, OnnxTensor>(states.size + 1)
            inputs[INPUT_AUDIO] = x
            inputs.putAll(states)
            var emitted = false
            try {
            m.encoder.run(inputs).use { out ->
                val encOut = out[0] as OnnxTensor
                val shape = encOut.info.shape // [1, T', 512]
                val outFrames = shape[1].toInt()
                val dim = shape[2].toInt()
                val buf = encOut.floatBuffer
                // Adopt the updated states BEFORE decoding, and pair by name
                // so the order of the output list is never assumed.
                val updated = HashMap<String, OnnxTensor>(states.size)
                try {
                    for ((outName, value) in out) {
                        if (outName == JOINER_ENC) continue // encoder_out, handled above
                        updated[outName.removePrefix(NEW_PREFIX)] = copyOf(value as OnnxTensor)
                    }
                } catch (t: Throwable) {
                    // A half-built state set is worse than none: free the
                    // copies made so far rather than dropping their handles.
                    updated.values.forEach { runCatching { it.close() } }
                    throw t
                }
                states.values.forEach { runCatching { it.close() } }
                states.putAll(updated)
                val frame = FloatArray(dim)
                for (t in 0 until outFrames) {
                    buf.position(t * dim)
                    buf.get(frame, 0, dim)
                    if (decodeFrame(frame)) emitted = true
                }
            }
            } finally {
                // ORT copies the input on run(), so x is ours to free — and it
                // must be freed on the throwing path too, which is exactly
                // where an unclosed OnnxTensor leaks for the process lifetime.
                runCatching { x.close() }
            }
            emitted
        }

        /** ORT owns the result tensors; copy before the results are closed. */
        private fun copyOf(t: OnnxTensor): OnnxTensor {
            val shape = t.info.shape
            return if (t.info.type == ai.onnxruntime.OnnxJavaType.INT64) {
                val b = t.longBuffer
                val a = LongArray(b.remaining()); b.get(a)
                OnnxTensor.createTensor(env, LongBuffer.wrap(a), shape)
            } else {
                val b = t.floatBuffer
                val a = FloatArray(b.remaining()); b.get(a)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)
            }
        }

        /** Greedy transducer step for one encoder frame; true if a token landed. */
        private fun decodeFrame(encFrame: FloatArray): Boolean {
            var emitted = false
            var symbols = 0
            while (symbols < MAX_SYMBOLS_PER_FRAME) {
                val dec = decoderOut ?: runDecoder().also { decoderOut = it }
                val logits = runJoiner(encFrame, dec)
                var best = 0
                for (i in logits.indices) if (logits[i] > logits[best]) best = i
                if (best == BLANK) break
                hypothesis.add(best)
                decoderOut = null // context changed; the decoder must run again
                emitted = true
                symbols++
            }
            return emitted
        }

        private fun runDecoder(): FloatArray {
            val ctx = LongArray(m.contextSize) { i ->
                val idx = hypothesis.size - m.contextSize + i
                if (idx < 0) BLANK.toLong() else hypothesis[idx].toLong()
            }
            OnnxTensor.createTensor(env, LongBuffer.wrap(ctx), longArrayOf(1, m.contextSize.toLong())).use { y ->
                m.decoder.run(mapOf(DECODER_IN to y)).use { r ->
                    val b = (r[0] as OnnxTensor).floatBuffer
                    return FloatArray(b.remaining()).also { b.get(it) }
                }
            }
        }

        private fun runJoiner(encFrame: FloatArray, decOut: FloatArray): FloatArray {
            val e = OnnxTensor.createTensor(env, FloatBuffer.wrap(encFrame), longArrayOf(1, encFrame.size.toLong()))
            val d = OnnxTensor.createTensor(env, FloatBuffer.wrap(decOut), longArrayOf(1, decOut.size.toLong()))
            try {
                m.joiner.run(mapOf(JOINER_ENC to e, JOINER_DEC to d)).use { r ->
                    val b = (r[0] as OnnxTensor).floatBuffer
                    return FloatArray(b.remaining()).also { b.get(it) }
                }
            } finally {
                runCatching { e.close() }
                runCatching { d.close() }
            }
        }

        /** BPE pieces joined: "▁" opens a word, everything else continues one. */
        fun text(): String = buildString {
            for (id in hypothesis) {
                val piece = m.tokens.getOrNull(id) ?: continue
                if (piece.startsWith(WORD_START)) {
                    if (isNotEmpty()) append(' ')
                    append(piece.removePrefix(WORD_START))
                } else {
                    append(piece)
                }
            }
        }.trim()

        fun close() {
            states.values.forEach { runCatching { it.close() } }
            states.clear()
        }
    }

    /**
     * Pay the asset extraction and the three session builds NOW, off the
     * turn — the same contract every other bundled engine's warmUp has.
     */
    fun warmUp() {
        runCatching { load() }
            .onFailure { Log.w(TAG, "streaming warmup failed: ${it.message}") }
    }

    /** A fresh utterance. The caller owns it and must [Stream.close] it. */
    fun newStream(): Stream = Stream(load())

    override fun close() = sessionLock.write {
        synchronized(this) {
            models?.let {
                runCatching { it.encoder.close() }
                runCatching { it.decoder.close() }
                runCatching { it.joiner.close() }
            }
            models = null
        }
    }

    private companion object {
        const val TAG = "TukiStream"
        const val ASSET_DIR = "asr-stream"
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"

        const val INPUT_AUDIO = "x"
        const val NEW_PREFIX = "new_"
        const val PROCESSED_LENS = "processed_lens"
        const val DECODER_IN = "y"
        const val JOINER_ENC = "encoder_out"
        const val JOINER_DEC = "decoder_out"

        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val BLANK = 0
        const val WORD_START = "▁"

        /** Used only if a future export drops its metadata. */
        const val DEFAULT_WINDOW = 45
        const val DEFAULT_STRIDE = 32

        /** A transducer can emit several tokens on one frame; this bounds the
         *  pathological case where it would emit forever. */
        const val MAX_SYMBOLS_PER_FRAME = 5

        /** ln(FLT_EPSILON): what [KaldiFbank] produces for digital silence. */
        const val SILENCE_LOGMEL = -15.942385f
    }
}
