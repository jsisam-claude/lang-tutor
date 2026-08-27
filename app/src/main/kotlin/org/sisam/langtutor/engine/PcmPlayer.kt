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

    fun play(audio: FloatArray) {
        // Every path to audible speech goes through here, which makes it the
        // one honest place to close the "learner stopped -> first sound"
        // measurement. No-op unless a turn is being timed.
        TurnLatency.firstAudio()
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
        var offset = 0
        while (offset < audio.size && !interrupted) {
            val n = t.write(audio, offset, audio.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            offset += n
        }
        framesWritten += offset // mono: one sample == one frame
        val deadline = System.currentTimeMillis() + (audio.size * 1000L / sampleRate) + DRAIN_GRACE_MS
        while (!interrupted && t.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
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
        const val DRAIN_GRACE_MS = 700L
    }
}
