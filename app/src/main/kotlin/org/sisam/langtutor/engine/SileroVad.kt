package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer
import org.sisam.langtutor.speech.VadGate

/**
 * Bundled voice-activity detector — Silero VAD v6.2.1 (MIT), 2.3 MB ONNX,
 * small enough to ship INSIDE the APK (assets), so hands-free listening needs
 * no download and works on a de-googled phone out of the box.
 *
 * Stateful and strictly sequential: the model carries an LSTM state across the
 * fixed 512-sample (32 ms) frames of one utterance, so [reset] must be called
 * before each turn. Measured in-container at 0.21 ms/frame — ~150× realtime,
 * i.e. free next to the ASR.
 */
class SileroVad(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var state = FloatArray(STATE_SIZE)

    private val session: OrtSession by lazy {
        EngineStatus.step(EngineStatus.Kind.VAD_LOAD, ASSET) {
            val started = System.nanoTime()
            val bytes = appContext.assets.open(ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1) // tiny model; one thread avoids pool churn
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            OrtEnvironment.getEnvironment().createSession(bytes, opts).also {
                Log.i(TAG, "vad loaded (${bytes.size / 1024}KB) in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }
    }

    // v6 contract: 64 samples of the PREVIOUS frame are prepended to each 512-
    // sample frame (input [1,576]) — the official OnnxWrapper does exactly this.
    // Feeding a bare 512 frame is not an error; it just returns ~0.0 on real
    // speech, which is how the contract difference was first found.
    private val context = FloatArray(CONTEXT)
    private val window = FloatArray(CONTEXT + FRAME)

    /** Speech probability for one [VadGate.Config.windowSamples]-sample frame. */
    fun probability(frame: FloatArray): Float {
        require(frame.size == FRAME) { "silero needs exactly $FRAME samples, got ${frame.size}" }
        System.arraycopy(context, 0, window, 0, CONTEXT)
        System.arraycopy(frame, 0, window, CONTEXT, FRAME)
        System.arraycopy(frame, FRAME - CONTEXT, context, 0, CONTEXT)
        val env = OrtEnvironment.getEnvironment()
        val inputs = mapOf(
            "input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, (CONTEXT + FRAME).toLong())),
            "state" to OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)),
            "sr" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf()),
        )
        try {
            session.run(inputs).use { results ->
                val prob = (results[0] as OnnxTensor).floatBuffer.get(0)
                val next = (results[1] as OnnxTensor).floatBuffer
                val updated = FloatArray(STATE_SIZE)
                next.rewind()
                next.get(updated, 0, minOf(STATE_SIZE, next.remaining()))
                state = updated
                return prob
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /** Clears the recurrent state — MUST be called before every utterance. */
    fun reset() {
        state = FloatArray(STATE_SIZE)
        context.fill(0f)
    }

    /** Loads the session up front so the first turn doesn't pay for it. */
    fun warmUp() {
        runCatching { probability(FloatArray(FRAME)) }
        reset()
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "TukiVad"
        const val ASSET = "vad/silero_vad.onnx"
        const val FRAME = 512
        /** v6: samples of prior audio prepended to each frame (see probability). */
        private const val CONTEXT = 64
        const val SAMPLE_RATE = 16_000
        private const val STATE_SIZE = 2 * 1 * 128

        /** True when the model file is packed in this build. */
        fun isAvailable(context: Context): Boolean =
            runCatching { context.assets.open(ASSET).close() }.isSuccess
    }
}
