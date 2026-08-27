package org.sisam.langtutor.speech

import kotlin.math.PI
import kotlin.math.sin

/**
 * The parrot, as DSP: what turns Tuki's clean human voice into a cartoon
 * bird for PERSONALITY lines — and only those.
 *
 * The doctrine this encodes: **the effect must never touch the teaching
 * voice.** A line the child is meant to copy has to be the most intelligible
 * audio in the app, so drill targets, vocab, conversation replies and Hebrew
 * all bypass this file entirely; praise and encouragement — lines whose exact
 * phonetics nobody is learning from — are where the character lives.
 *
 * The recipe is the one every animated bird uses:
 *
 * - **Pitch up WITH the formants.** The engine synthesizes the line slowed by
 *   [PITCH], and [resample] then shortens it back — duration lands where it
 *   started, pitch and formants rise together. Formant shift is normally the
 *   flaw of naive resampling; here it is the point, because raised formants
 *   are what read as "small creature" rather than "sped-up adult".
 * - **A gentle warble**: slow amplitude modulation, the birdy flutter.
 * - **A flourish**: a short rising trill before the line, synthesized like
 *   the reward chimes — no assets, no licences.
 *
 * Pure JVM and allocation-light: one pass per stage over a clip that is a
 * couple of seconds long at most.
 */
object ParrotEffect {

    /** ~3.4 semitones up. Cartoon-parrot territory starts around 2 and stops
     *  being intelligible past ~5. */
    const val PITCH = 1.22f

    /** Natural-vibrato territory; faster reads as a fault in the speaker. */
    const val WARBLE_HZ = 6.5f
    const val WARBLE_DEPTH = 0.10f

    /** The full treatment for a synthesized line. */
    fun apply(audio: FloatArray, sampleRate: Int, pitch: Float = PITCH): FloatArray =
        warble(resample(audio, pitch), sampleRate, WARBLE_HZ, WARBLE_DEPTH)

    /**
     * Linear resample: shortens by [factor], raising pitch AND formants by the
     * same amount. Callers pre-lengthen at synthesis time (speed / factor) so
     * the final duration matches what was asked for.
     */
    fun resample(audio: FloatArray, factor: Float): FloatArray {
        if (audio.isEmpty() || factor == 1f) return audio
        val outLen = (audio.size / factor).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val last = audio.size - 1
        for (i in out.indices) {
            val pos = i * factor
            val i0 = pos.toInt().coerceAtMost(last)
            val i1 = (i0 + 1).coerceAtMost(last)
            val frac = pos - i0
            out[i] = audio[i0] + (audio[i1] - audio[i0]) * frac
        }
        return out
    }

    /** Slow AM flutter. Depth is small on purpose: at 10% the ear hears a
     *  living tremble, at 30% it hears a broken speaker. */
    fun warble(audio: FloatArray, sampleRate: Int, hz: Float, depth: Float): FloatArray {
        val out = FloatArray(audio.size)
        val w = 2.0 * PI * hz / sampleRate
        for (i in audio.indices) {
            out[i] = audio[i] * (1f + depth * sin(w * i).toFloat())
        }
        return out
    }

    /**
     * The "brrp!" — a rising sweep with a fast trill, played before a
     * flavored line. Phase-accumulated so the sweep is clean, half-sine
     * enveloped so it clicks at neither end, and quiet ([FLOURISH_PEAK])
     * because it is an announcement, not a jump scare.
     */
    fun flourish(sampleRate: Int): FloatArray {
        val n = sampleRate * FLOURISH_MS / 1000
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val progress = i / n.toDouble()
            val freq = SWEEP_FROM_HZ + (SWEEP_TO_HZ - SWEEP_FROM_HZ) * progress
            phase += 2.0 * PI * freq / sampleRate
            val trill = 1.0 + 0.5 * sin(2.0 * PI * TRILL_HZ * t)
            val envelope = sin(PI * progress)
            out[i] = (FLOURISH_PEAK * envelope * trill * sin(phase)).toFloat()
        }
        return out
    }

    private const val FLOURISH_MS = 140
    private const val SWEEP_FROM_HZ = 700.0
    private const val SWEEP_TO_HZ = 1_600.0
    private const val TRILL_HZ = 28.0
    private const val FLOURISH_PEAK = 0.17
}
