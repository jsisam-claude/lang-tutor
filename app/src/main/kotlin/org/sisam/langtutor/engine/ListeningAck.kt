package org.sisam.langtutor.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The "heard you" blip at the end of a learner's turn.
 *
 * Measured rounds run ten-plus seconds from mic release to Tuki's first word,
 * and no engine work can make that instant. What CAN be instant is the
 * acknowledgement: humans read a fast backchannel as "you were heard", and it
 * buys the pipeline a second or two of natural-feeling time
 * (docs/latency.md item 3). So this plays at the moment the turn ends — mic
 * release, or the hands-free endpoint — before ASR has even returned.
 *
 * Everything about the mechanism is chosen for start latency:
 *
 * - **MODE_STATIC, rendered once, kept.** Replaying is stop + rewind + play —
 *   no synthesis, no write, under 20 ms to sounding. A SoundPool adds its own
 *   20-50 ms and a pre-warmed low-latency stream keeps the audio DSP awake
 *   between turns, which is battery spent on silence.
 * - **Synthesized, not shipped** — same doctrine as [RewardChime]: no asset,
 *   no licence, no sample-rate mismatch. A rising fourth, very soft.
 * - **Quieter than everything else.** This sounds on EVERY turn, hundreds of
 *   times an hour against the reward chime's handful; at reward loudness it
 *   would be the most-heard thing in the app. Peak sits at about half the
 *   chime's, well under the voice.
 *
 * On USAGE_MEDIA like the voice and the chime, for the same recorded reason:
 * it must ride the volume control the learner actually has.
 */
object ListeningAck {

    private const val SAMPLE_RATE = 22_050

    @Volatile private var track: AudioTrack? = null

    /** Non-blocking: hands the cue to the mixer and returns. */
    fun play() {
        val t = track ?: synchronized(this) {
            track ?: runCatching { buildTrack(render()) }.getOrNull()?.also { track = it }
        } ?: return
        runCatching {
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    /** Build the track off the turn path, so the FIRST turn's ack is as fast
     *  as every later one. Cheap enough to call from any warm-up. */
    fun warmUp() {
        if (track == null) synchronized(this) {
            if (track == null) track = runCatching { buildTrack(render()) }.getOrNull()
        }
    }

    /** A few KB of static AudioTrack — free to give back under pressure; the
     *  next turn quietly re-renders it. */
    fun release() = synchronized(this) {
        track?.let { runCatching { it.release() } }
        track = null
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        built.write(pcm, 0, pcm.size)
        return built
    }

    /**
     * Two soft notes a fourth apart, rising — the shape of "mm-hm?". Rendered
     * with [RewardChime]'s bell recipe (soft attack, exponential tail, a touch
     * of harmonics) but shorter, quieter, and deliberately unceremonious: it
     * must acknowledge without ever feeling like a judgement of the answer.
     */
    private fun render(): ShortArray {
        data class Note(val freq: Double, val startMs: Int, val holdMs: Int)
        val notes = listOf(
            Note(freq = 587.33, startMs = 0, holdMs = 70),  // D5
            Note(freq = 783.99, startMs = 55, holdMs = 90), // G5
        )
        val tailMs = 90
        val attackSamples = (SAMPLE_RATE * 5 / 1000).coerceAtLeast(1)
        val totalMs = notes.maxOf { it.startMs + it.holdMs } + tailMs
        val total = SAMPLE_RATE * totalMs / 1000
        val mix = DoubleArray(total)
        for (note in notes) {
            val start = SAMPLE_RATE * note.startMs / 1000
            val length = SAMPLE_RATE * (note.holdMs + tailMs) / 1000
            val decay = 40.0 / (note.holdMs / 1000.0) * LN10_OVER_20
            for (i in 0 until min(length, total - start)) {
                val t = i / SAMPLE_RATE.toDouble()
                val env = min(1.0, i / attackSamples.toDouble()) * exp(-decay * t)
                mix[start + i] += env * (
                    sin(2 * PI * note.freq * t) + 0.25 * sin(4 * PI * note.freq * t)
                    )
            }
        }
        val peak = mix.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        val scale = if (peak > 0) PEAK / peak else 0.0
        return ShortArray(total) { i ->
            (mix[i] * scale * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private const val LN10_OVER_20 = 0.1151292546497023

    /** About half the reward chime's 0.34 — heard every turn, so it must sit
     *  under everything, the voice most of all. */
    private const val PEAK = 0.17
}
