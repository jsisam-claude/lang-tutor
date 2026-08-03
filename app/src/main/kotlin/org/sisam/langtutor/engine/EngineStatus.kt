package org.sisam.langtutor.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the engines are doing right now, for anyone who has to wait for them.
 *
 * Every model here loads lazily and some of them are hundreds of megabytes, so
 * the first mic press or the first Hebrew line can sit still for tens of
 * seconds. Without a signal that is indistinguishable from a hang — a child
 * gives up, and a tester files a bug against the wrong thing.
 *
 * Two audiences, one call site:
 *  - the child sees a short reassuring line ([Kind.labelRes]) with a seconds
 *    counter, so waiting looks like waiting;
 *  - logcat gets `TukiStep` lines with the technical detail and the elapsed
 *    milliseconds of every step, which is the bench data we actually want off
 *    a device.
 *
 * Steps nest (a transcription triggers the model load on first use), so the
 * active step is a stack and the UI always shows the innermost one — the thing
 * genuinely being waited on.
 */
object EngineStatus {

    /** A slow operation, coarse enough that a child-facing label makes sense. */
    enum class Kind {
        LLM_LOAD,
        LLM_GENERATE,
        ASR_LOAD,
        ASR_RUN,
        TTS_LOAD,
        TTS_RUN,
        HEBREW_LOAD,
        HEBREW_RUN,
        COACH_LOAD,
        COACH_RUN,
        VAD_LOAD,
    }

    data class Step(
        val kind: Kind,
        /** Technical detail: file name, backend, window count. Logs + debug UI. */
        val detail: String,
        val startedAtMillis: Long,
    )

    private val stack = ArrayDeque<Step>()
    private val _current = MutableStateFlow<Step?>(null)

    /** The innermost running step, or null when nothing slow is in flight. */
    val current: StateFlow<Step?> = _current.asStateFlow()

    /**
     * Run [block] as a reported step. Logs entry and exit with elapsed ms, and
     * publishes it for the UI while it runs. Nesting-safe and exception-safe:
     * the previous step is restored even when [block] throws, and the throw
     * propagates untouched.
     */
    inline fun <T> step(kind: Kind, detail: String = "", block: () -> T): T {
        begin(kind, detail)
        var failure: Throwable? = null
        try {
            return block()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            end(failure)
        }
    }

    @PublishedApi
    internal fun begin(kind: Kind, detail: String) {
        val step = Step(kind, detail, System.currentTimeMillis())
        synchronized(stack) {
            stack.addLast(step)
            _current.value = step
        }
        Log.i(TAG, "▶ $kind${if (detail.isEmpty()) "" else " $detail"}")
    }

    @PublishedApi
    internal fun end(failure: Throwable?) {
        val finished = synchronized(stack) {
            val done = stack.removeLastOrNull()
            _current.value = stack.lastOrNull()
            done
        } ?: return
        val ms = System.currentTimeMillis() - finished.startedAtMillis
        val detail = if (finished.detail.isEmpty()) "" else " ${finished.detail}"
        if (failure == null) {
            Log.i(TAG, "✔ ${finished.kind}$detail in ${ms}ms")
        } else {
            Log.w(TAG, "✖ ${finished.kind}$detail failed after ${ms}ms: ${failure.message}")
        }
    }

    const val TAG = "TukiStep"
}
