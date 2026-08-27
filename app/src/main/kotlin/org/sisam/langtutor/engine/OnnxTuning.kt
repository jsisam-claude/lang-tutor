package org.sisam.langtutor.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File

/**
 * How every bundled ONNX model is asked to run — in ONE place, because five
 * engines each independently guessing the same wrong number is what got us
 * here.
 *
 * Two decisions, both measured rather than assumed:
 *
 * **Threads.** Every engine used to ask for 4, sized from
 * `availableProcessors` (8 on a Pixel 9). But a Tensor G4 is 1x Cortex-X4 +
 * 3x A720 + 4x A520 — only FOUR fast cores — so a 4-thread session saturates
 * exactly the cores that get hot and spills nothing onto the little ones. On
 * device that drove the X4 to 90 C junction with the skin at 39 C and the
 * system in THERMAL_STATUS_LIGHT, and every CPU engine ran ~2x slower after
 * four minutes of use (TTS RTF 1.02 -> 1.96 on identical work). Below the
 * throttle point fewer threads are also simply faster, because clamped cores
 * do not go faster for being asked twice.
 *
 * **XNNPACK.** No execution provider was configured anywhere, so these models
 * ran on ORT's portable reference kernels. XNNPACK is ARM-tuned and ships in
 * the Android ORT package.
 *
 * The XNNPACK attempt carries the same crash-hint machinery as the LLM's GPU
 * attempt, and for the same hard-won reason: a native crash inside a provider
 * kills the process where Kotlin catches nothing, so a marker file written
 * BEFORE the attempt and cleared after is the only thing that can stop a
 * crash loop. Graph optimization deliberately stays at BASIC: ORT's extended
 * optimizer was seen to crash on the Kokoro graph, and that is one variable
 * per install.
 */
object OnnxTuning {

    private const val TAG = "TukiOnnx"

    /** Marker written before an XNNPACK attempt, deleted after it survives. */
    private const val ATTEMPT_SUFFIX = ".xnnpack-attempt"

    /** Written when XNNPACK is known bad for this install. */
    private const val HINT_SUFFIX = ".xnnpack-skip"

    /**
     * Threads for a heavy model: the fast cores, never all of them.
     *
     * `availableProcessors / 2` lands on 4 for an 8-core big.LITTLE, which is
     * the count that cooks. Capped at 3 so at least one fast core stays free
     * for the LLM's own CPU-side work (sampling and detokenization run there
     * even when decode is on the GPU) and for the audio thread that must not
     * underrun.
     */
    val heavyThreads: Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 3)

    /**
     * Create a session with the tuned options, falling back to the portable
     * kernels if XNNPACK cannot bind — or crashed the process last time.
     *
     * @param stamp identifies this install, so an app update retries XNNPACK
     *   once and re-decides (same contract as the LLM's cpu hint).
     */
    fun createSession(
        modelPath: String,
        label: String,
        stamp: String,
        threads: Int = heavyThreads,
    ): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val hint = File("$modelPath$HINT_SUFFIX")
        val marker = File("$modelPath$ATTEMPT_SUFFIX")

        val crashed = marker.exists()
        val hinted = runCatching { hint.isFile && hint.readText().trim() == stamp }.getOrDefault(false)
        if (crashed) {
            Log.w(TAG, "$label: previous XNNPACK attempt crashed the process — pinning portable kernels")
            runCatching { hint.writeText(stamp) }
            runCatching { marker.delete() }
        }

        if (!crashed && !hinted) {
            runCatching { marker.writeText(stamp) }
            val session = runCatching {
                env.createSession(modelPath, options(threads, xnnpack = true))
            }.getOrElse { e ->
                Log.w(TAG, "$label: XNNPACK unavailable (${e.message}) — portable kernels", e)
                null
            }
            runCatching { marker.delete() }
            if (session != null) {
                runCatching { if (hint.exists()) hint.delete() }
                Log.i(TAG, "$label: XNNPACK, $threads threads")
                return session
            }
            runCatching { hint.writeText(stamp) }
        } else if (hinted) {
            Log.i(TAG, "$label: XNNPACK skipped for this install — delete $HINT_SUFFIX to retry")
        }

        Log.i(TAG, "$label: portable kernels, $threads threads")
        return env.createSession(modelPath, options(threads, xnnpack = false))
    }

    private fun options(threads: Int, xnnpack: Boolean): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            if (xnnpack) {
                // XNNPACK runs its own pool; ORT's guidance is to hand the
                // session a single intra-op thread so the two do not fight.
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                setIntraOpNumThreads(1)
            } else {
                setIntraOpNumThreads(threads)
            }
            // BASIC, not ALL: ORT's extended optimizer crashed on the Kokoro
            // graph in testing. One variable per install.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
}
