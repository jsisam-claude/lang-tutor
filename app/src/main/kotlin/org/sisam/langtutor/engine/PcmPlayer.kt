package org.sisam.langtutor.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Blocking float-PCM playback shared by the bundled TTS engines (Kokoro 24 kHz,
 * Hebrew Piper 22.05 kHz). One streaming AudioTrack per utterance; [play]
 * writes a synthesized chunk and DRAINS it — "done" must mean finished
 * SOUNDING, because the orchestrator opens the mic right afterwards and must
 * not hear Tuki. playbackHeadPosition counts from track start, so draining
 * compares against the cumulative frames written across all chunks.
 */
class PcmPlayer(private val sampleRate: Int) {

    @Volatile private var track: AudioTrack? = null
    private var framesWritten = 0

    /** Interrupt hook: checked between writes and while draining. */
    @Volatile var interrupted = false

    /**
     * [onProgress], when given, is called from this (blocking) thread with
     * the playback head's offset into THIS chunk, in frames — once per write
     * and once per 20 ms drain poll. It is how karaoke tracks the word being
     * SPOKEN rather than the word being written: writes run up to a buffer
     * ahead of sound, but the head does not lie.
     */
    fun play(audio: FloatArray, onProgress: ((Int) -> Unit)? = null) {
        // Every path to audible speech goes through here, which makes it the
        // one honest place to close the "learner stopped -> first sound"
        // measurement. No-op unless a turn is being timed.
        TurnLatency.firstAudio()
        // While this thread feeds the AudioTrack it competes with LLM decode
        // and the next group's synthesis for saturated cores — exactly when an
        // underrun (an audible pop or stutter) is likeliest. Raise it to the
        // platform's audio priority for the duration, and RESTORE it after:
        // play() runs on a pooled dispatcher thread, and a priority left
        // behind would quietly ride along under unrelated work later.
        val tid = android.os.Process.myTid()
        val prior = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        try {
            playAt(audio, onProgress)
        } finally {
            prior?.let { p -> runCatching { android.os.Process.setThreadPriority(tid, p) } }
        }
    }

    private fun playAt(audio: FloatArray, onProgress: ((Int) -> Unit)?) {
        // The one place sound becomes real — logged so a transcript can tell
        // "synthesized but never played" from "played but not heard". A drill
        // report of exactly that ambiguity is why these lines exist.
        if (interrupted) {
            android.util.Log.i(TAG, "SKIP ${audio.size * 1000L / sampleRate}ms clip (interrupted before write)")
            return
        }
        val t = track ?: AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(sampleRate * Float.SIZE_BYTES) // 1 s of headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                track = it
                framesWritten = 0
            }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        // The head counts from track start across chunks; progress reports
        // are relative to THIS chunk's first frame.
        val base = framesWritten
        var offset = 0
        while (offset < audio.size && !interrupted) {
            val n = t.write(audio, offset, audio.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            offset += n
            if (!interrupted) onProgress?.invoke((t.playbackHeadPosition - base).coerceAtLeast(0))
        }
        framesWritten += offset // mono: one sample == one frame
        val deadline = System.currentTimeMillis() + (audio.size * 1000L / sampleRate) + DRAIN_GRACE_MS
        while (!interrupted && t.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) {
            onProgress?.invoke((t.playbackHeadPosition - base).coerceAtLeast(0))
            Thread.sleep(20)
        }
        // Not when interrupted: stop() has already cleared the karaoke
        // position, and a final report here would resurrect it as a stale
        // highlight after the voice went quiet.
        if (!interrupted) onProgress?.invoke(audio.size)
        android.util.Log.i(
            TAG,
            "played ${audio.size * 1000L / sampleRate}ms clip: wrote=$offset/${audio.size} " +
                "head=${t.playbackHeadPosition - base} state=${t.playState}" +
                (if (interrupted) " INTERRUPTED" else ""),
        )
    }

    fun release() {
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
    }

    private companion object {
        const val TAG = "TukiAudio"
        const val DRAIN_GRACE_MS = 700L
    }
}
