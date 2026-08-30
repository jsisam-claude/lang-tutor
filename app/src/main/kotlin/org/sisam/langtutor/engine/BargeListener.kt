package org.sisam.langtutor.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * The voice-barge-in PROBE (experimental, off by default): while Tuki is
 * talking, listen on the ECHO-CANCELLED voice path and hush him when the
 * learner starts speaking over him — the full-duplex half of real
 * conversation, and the device experiment docs/latency.md's barge-in notes
 * ask for.
 *
 * Everything uncertain about this feature is a DEVICE fact, so this class is
 * built to report as much as to act:
 *
 * - `VOICE_COMMUNICATION` + [AcousticEchoCanceler] should subtract Tuki's own
 *   voice; whether it actually does with playback on USAGE_MEDIA (the
 *   recorded design tension — hardware AEC formally wants the voice usage) is
 *   exactly what the log shows: per-second speech-probability stats while
 *   ONLY Tuki is talking are the self-trigger measurement.
 * - Whether this stream can open at all while the rest of the app owns audio
 *   is the concurrent-capture question — an open failure is logged, never
 *   thrown.
 *
 * Triggering is deliberately strict — [TRIGGER_FRAMES] consecutive confident
 * frames (~300 ms of sustained speech) — because a false barge cuts Tuki off
 * mid-sentence for nothing. The AEC'd audio is used for DETECTION ONLY and
 * discarded; the doctrine that ASR gets raw audio is untouched.
 */
class BargeListener(private val vad: SileroVad) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    /** Begin listening; [onBarge] fires once, from the capture thread, after
     *  sustained speech — then the listener stops itself. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO requested by the room
    fun start(onBarge: () -> Unit) {
        if (running) return
        running = true
        thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val readSize = maxOf(minBuf, SileroVad.FRAME * 4) / SileroVad.FRAME * SileroVad.FRAME
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL, ENCODING, readSize * 4,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "voice-comm record unavailable: ${t.message}")
                running = false
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                // The concurrent-capture answer, when it is "no".
                Log.w(TAG, "voice-comm record failed to initialize (concurrent capture denied?)")
                rec.release()
                running = false
                return@Thread
            }
            recorder = rec
            // The platform effects, explicitly, so the log states what this
            // device actually gave us rather than what we hoped for.
            val aec = runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
                } else null
            }.getOrNull()
            val ns = runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
                } else null
            }.getOrNull()
            Log.i(
                TAG,
                "probe up: aec=${aec?.enabled ?: "unavailable"} ns=${ns?.enabled ?: "unavailable"} " +
                    "session=${rec.audioSessionId}",
            )
            try {
                rec.startRecording()
                val buf = ShortArray(readSize)
                val frame = FloatArray(SileroVad.FRAME)
                vad.reset()
                var consecutive = 0
                var statMax = 0f
                var statSum = 0f
                var statN = 0
                var lastStatAt = System.currentTimeMillis()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var off = 0
                    while (running && off + SileroVad.FRAME <= n) {
                        for (i in 0 until SileroVad.FRAME) frame[i] = buf[off + i] / 32768f
                        off += SileroVad.FRAME
                        val p = runCatching { vad.probability(frame) }.getOrNull() ?: continue
                        statMax = maxOf(statMax, p)
                        statSum += p
                        statN++
                        consecutive = if (p >= TRIGGER_PROBABILITY) consecutive + 1 else 0
                        if (consecutive >= TRIGGER_FRAMES) {
                            Log.i(TAG, "BARGE: sustained speech over playback (${TRIGGER_FRAMES} frames >= $TRIGGER_PROBABILITY)")
                            running = false
                            runCatching(onBarge)
                            break
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastStatAt >= 1000 && statN > 0) {
                        // The self-trigger measurement: while ONLY Tuki talks,
                        // these numbers ARE the AEC verdict.
                        Log.i(
                            TAG,
                            "probe: max=%.2f avg=%.2f over %d frames".format(
                                statMax, statSum / statN, statN,
                            ),
                        )
                        statMax = 0f; statSum = 0f; statN = 0
                        lastStatAt = now
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "probe loop failed", t)
            } finally {
                runCatching { aec?.release() }
                runCatching { ns?.release() }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                recorder = null
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        thread = null
    }

    private companion object {
        const val TAG = "TukiBarge"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** High bar on purpose: a false barge cuts Tuki off for nothing. */
        const val TRIGGER_PROBABILITY = 0.65f

        /** ~300 ms of continuous speech at Silero's 32 ms frames. */
        const val TRIGGER_FRAMES = 9
    }
}
