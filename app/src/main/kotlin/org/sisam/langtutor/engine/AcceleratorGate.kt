package org.sisam.langtutor.engine

import android.util.Log
import java.util.concurrent.locks.ReentrantLock

/**
 * One native accelerator may be initialising at a time, app-wide.
 *
 * Two reasons, both learned the hard way on 2026-08-27.
 *
 * **Crashes.** The LiteRT GPU delegate and LiteRT-LM's own accelerator were
 * seen initialising 96 ms apart, and the process died natively twice in a row,
 * taking the language model's GPU backend down for the whole install. Separate
 * runtimes reaching for the same device at the same moment is not a
 * combination anyone tests.
 *
 * **Attribution.** Every accelerator attempt here is guarded by a marker file
 * written before it and deleted after — the only thing that can catch a native
 * crash, since it kills Kotlin along with everything else. That machinery
 * assumes the crash belongs to the attempt in flight. When two attempts
 * overlap, it blames the wrong one: Kokoro's XNNPACK session took 8.3 s to
 * build while the LLM's GPU attempt crashed underneath it, and XNNPACK was
 * pinned off for the install despite being entirely innocent. Serialising the
 * attempts makes the marker mean what it claims.
 *
 * Only INITIALISATION is serialised — inference runs concurrently as before.
 */
object AcceleratorGate {

    private const val TAG = "TukiOnnx"
    private val lock = ReentrantLock()

    /** Run [block] with exclusive rights to bring up an accelerator. */
    fun <T> exclusive(label: String, block: () -> T): T {
        val contended = lock.isLocked && !lock.isHeldByCurrentThread
        if (contended) Log.i(TAG, "$label: waiting — another accelerator is initialising")
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
