package org.sisam.langtutor.engine

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * The one number that IS responsiveness: **the learner stopped, to the first
 * sound back**.
 *
 * Every other timing we log is a part — a load, a decode, a synthesis — and a
 * part can look fine while the whole feels broken. Nothing measured the whole,
 * so "the app is not so responsive" could only be answered by adding up step
 * lines by hand across a paste of logcat. This closes that.
 *
 * Deliberately marked at the moment the CHILD finishes (mic release, or Send),
 * not when some engine starts, because the wait they experience begins there —
 * including the parts we would rather not count, like a cold model load.
 */
object TurnLatency {

    private const val TAG = "TukiLatency"

    private val pending = AtomicReference<Pair<String, Long>?>(null)

    /** The learner just finished; the clock they feel starts now. */
    fun mark(what: String) {
        pending.set(what to SystemClock.elapsedRealtime())
    }

    /** First audio of the reply reached the speaker. */
    fun firstAudio() {
        val (what, started) = pending.getAndSet(null) ?: return
        val ms = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "first audio ${ms}ms after $what${Thermal.suffix()}")
    }

    /** The turn ended without audio (blocked, barged, silent). */
    fun clear() {
        pending.set(null)
    }
}
