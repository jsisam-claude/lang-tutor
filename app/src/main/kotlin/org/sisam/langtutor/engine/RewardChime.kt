package org.sisam.langtutor.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import org.sisam.langtutor.ui.reward.RewardKind

/**
 * The sound of getting something right, synthesized rather than shipped.
 *
 * Same reasoning as the Canvas artwork: no audio files means no APK bytes, no
 * sample-rate mismatch, and — the one that actually decided it — no
 * third-party audio licence to audit. A triad is a handful of sines.
 *
 * Three deliberate choices, because this plays at a child hundreds of times:
 *
 * - **Consonant, always.** Each cue is a major triad or a clean interval. A
 *   reward sound that can grate is a reward sound the child learns to dread.
 * - **Quiet, on the SAME stream as the voice.** Peak amplitude is held near a
 *   third of full scale against the voice's ~0.46, which only means anything
 *   if both play through the same volume control — so this uses USAGE_MEDIA,
 *   exactly like [PcmPlayer]. Tagging it USAGE_ASSISTANCE_SONIFICATION instead
 *   would route it to the SYSTEM stream: silenced entirely on a phone in
 *   vibrate mode, and otherwise at whatever the ring volume happens to be
 *   relative to Tuki. The CONTENT_TYPE still says sonification, which is the
 *   honest description of what it is.
 * - **Short, with soft edges.** A 5 ms attack and an exponential tail: no
 *   click at either end, and it is over before it can become the point.
 */
class RewardChime(private val sampleRate: Int = 22_050) {

    /**
     * One AudioTrack per cue, rendered on first use and kept.
     *
     * MODE_STATIC with the samples already written is what makes [play] a
     * non-blocking, allocation-free call: replaying is stop + rewind + play,
     * with no synthesis and no buffer copy on the path between a child
     * answering correctly and hearing it. Four cues of well under a second at
     * 22 kHz mono is a few hundred kilobytes in total.
     */
    private val tracks = HashMap<RewardKind, AudioTrack>()

    /** Non-blocking: hands the cue to the mixer and returns. */
    fun play(kind: RewardKind) {
        val t = synchronized(tracks) {
            tracks.getOrPut(kind) { buildTrack(render(kind)) }
        }
        runCatching {
            // Static mode replays from the start only after a stop + reload;
            // without this a second correct answer plays silence.
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // MEDIA, matching PcmPlayer: the cue is mixed against
                    // Tuki's voice, so it has to share the voice's stream and
                    // volume control. USAGE_ASSISTANCE_SONIFICATION routes to
                    // STREAM_SYSTEM, which is muted in vibrate mode and scaled
                    // by the ring volume — the reward would vanish on a silent
                    // phone while the tutor kept talking.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        built.write(pcm, 0, pcm.size)
        return built
    }

    fun release() {
        synchronized(tracks) {
            tracks.values.forEach { runCatching { it.release() } }
            tracks.clear()
        }
    }

    /**
     * One cue per kind, each with its own shape:
     *
     * - COIN: a bright two-note flick, the classic "picked it up".
     * - STAR: a rising major triad — an arrival, not a pickup.
     * - FLAKE: a soft perfect fifth, barely there.
     * - MIX: the triad plus its octave, held a little longer. The only cue
     *   that is allowed to sound like an occasion.
     */
    private fun render(kind: RewardKind): ShortArray {
        val notes: List<Note> = when (kind) {
            RewardKind.COIN -> listOf(
                Note(freq = 987.77, startMs = 0, holdMs = 90),    // B5
                Note(freq = 1318.51, startMs = 70, holdMs = 220), // E6
            )
            RewardKind.STAR -> listOf(
                Note(freq = 523.25, startMs = 0, holdMs = 200),   // C5
                Note(freq = 659.25, startMs = 90, holdMs = 200),  // E5
                Note(freq = 783.99, startMs = 180, holdMs = 320), // G5
            )
            RewardKind.FLAKE -> listOf(
                Note(freq = 587.33, startMs = 0, holdMs = 260, gain = 0.6),  // D5
                Note(freq = 880.00, startMs = 60, holdMs = 320, gain = 0.5), // A5
            )
            RewardKind.MIX -> listOf(
                Note(freq = 523.25, startMs = 0, holdMs = 260),
                Note(freq = 659.25, startMs = 80, holdMs = 260),
                Note(freq = 783.99, startMs = 160, holdMs = 300),
                Note(freq = 1046.50, startMs = 250, holdMs = 460), // C6
            )
        }

        val totalMs = notes.maxOf { it.startMs + it.holdMs } + TAIL_MS
        val total = (sampleRate * totalMs / 1000.0).toInt()
        val mix = DoubleArray(total)

        for (note in notes) {
            val start = (sampleRate * note.startMs / 1000.0).toInt()
            val length = (sampleRate * (note.holdMs + TAIL_MS) / 1000.0).toInt()
            val attack = (sampleRate * ATTACK_MS / 1000.0).toInt().coerceAtLeast(1)
            // Decay chosen so the note is ~40 dB down by the end of its hold:
            // long enough to ring, short enough not to smear into the next.
            val decay = -DECAY_DB_PER_NOTE / (note.holdMs / 1000.0)
            for (i in 0 until min(length, total - start)) {
                val t = i / sampleRate.toDouble()
                val env = min(1.0, i / attack.toDouble()) * exp(decay * t * LN10_OVER_20)
                val w = note.gain * env * (
                    sin(2 * PI * note.freq * t) +
                        // A little second and third harmonic: a bare sine reads
                        // as a test tone, this reads as a bell.
                        0.30 * sin(4 * PI * note.freq * t) +
                        0.12 * sin(6 * PI * note.freq * t)
                    )
                mix[start + i] += w
            }
        }

        // Normalize to a fixed, modest peak. Fixed rather than per-cue, so the
        // four cues are at consistent loudness relative to each other.
        val peak = mix.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        val scale = if (peak > 0) PEAK / peak else 0.0
        return ShortArray(total) { i ->
            (mix[i] * scale * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private data class Note(
        val freq: Double,
        val startMs: Int,
        val holdMs: Int,
        val gain: Double = 1.0,
    )

    private companion object {
        const val ATTACK_MS = 5.0
        const val TAIL_MS = 120
        const val DECAY_DB_PER_NOTE = 40.0
        const val LN10_OVER_20 = 0.1151292546497023 // ln(10)/20: dB -> nepers
        /** Well below full scale: this must sit UNDER the tutor's voice. */
        const val PEAK = 0.34
    }
}
