package org.sisam.langtutor.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import org.jtransforms.fft.FloatFFT_1D

/**
 * Kaldi-compatible 80-bin log-mel filterbank — the feature contract the k2
 * streaming Zipformer was TRAINED on (docs/latency.md).
 *
 * This class exists because the encoder cannot tell you when its input is
 * wrong. Feed it features from a subtly different frontend — a Hann window
 * instead of Povey, dither left on, the DC offset not removed — and it does
 * not fail; it emits confident nonsense. So every constant here is the
 * kaldi-native-fbank default the icefall recipe uses, and the test suite
 * pins the pipeline stage by stage rather than trusting the arithmetic to
 * be obviously right.
 *
 * The defaults, all deliberate:
 * - 80 mel bins, 16 kHz, 25 ms frame (400 samples), 10 ms shift (160).
 * - `dither = 0`: training dithers for robustness, inference must not, or the
 *   same audio decodes differently twice.
 * - `remove_dc_offset`: each frame has its own mean subtracted, before
 *   pre-emphasis.
 * - Pre-emphasis 0.97, with the first sample using itself as its predecessor
 *   (kaldi's edge convention, not zero).
 * - Povey window: a Hann raised to 0.85 — kaldi's own, and NOT interchangeable
 *   with Hann.
 * - `snip_edges`: a frame that would run past the end of the audio is simply
 *   not produced, so frame count is `1 + (n - 400) / 160`.
 * - Power spectrum (magnitude squared), natural log, floored at FLT_EPSILON
 *   exactly as kaldi does — not at the smallest float, which would make
 *   digital silence read as -103 where kaldi says -15.9.
 */
class KaldiFbank(
    private val sampleRate: Int = 16_000,
    private val numBins: Int = 80,
    frameLengthMs: Float = 25f,
    frameShiftMs: Float = 10f,
    private val lowFreq: Float = 20f,
    highFreq: Float = 0f,
    private val preEmphasis: Float = 0.97f,
) {
    val frameLength: Int = (sampleRate * frameLengthMs / 1000f).toInt()
    val frameShift: Int = (sampleRate * frameShiftMs / 1000f).toInt()

    /** Zero above the frame length; kaldi pads to the next power of two. */
    private val fftSize: Int = run {
        var n = 1
        while (n < frameLength) n = n shl 1
        n
    }

    private val highCut: Float = if (highFreq <= 0f) sampleRate / 2f + highFreq else highFreq

    /** Povey window, precomputed: (0.5 - 0.5 cos(2πi/(N-1)))^0.85. */
    private val window: FloatArray = FloatArray(frameLength) { i ->
        val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (frameLength - 1))
        hann.pow(0.85).toFloat()
    }

    /**
     * The FFT plan, built once. JTransforms does all its work in the
     * constructor (twiddle and bit-reversal tables); rebuilding it per call
     * cost more than the transform itself, on the audio path.
     */
    private val fft = FloatFFT_1D(fftSize.toLong())

    /**
     * Triangular mel bins as (firstBinIndex, weights) — kaldi stores only the
     * non-zero span of each filter, which is also what keeps this loop cheap
     * enough to run inside a 320 ms chunk.
     */
    private val melBanks: List<Pair<Int, FloatArray>> = buildMelBanks()

    private fun mel(hz: Float): Float = (1127.0 * ln(1.0 + hz / 700.0)).toFloat()

    private fun buildMelBanks(): List<Pair<Int, FloatArray>> {
        val numFftBins = fftSize / 2
        val fftBinWidth = sampleRate.toFloat() / fftSize
        val melLow = mel(lowFreq)
        val melHigh = mel(highCut)
        val melDelta = (melHigh - melLow) / (numBins + 1)
        return (0 until numBins).map { bin ->
            val leftMel = melLow + bin * melDelta
            val centerMel = melLow + (bin + 1) * melDelta
            val rightMel = melLow + (bin + 2) * melDelta
            var first = -1
            val weights = ArrayList<Float>()
            for (i in 0 until numFftBins) {
                val freqMel = mel(fftBinWidth * i)
                if (freqMel <= leftMel || freqMel >= rightMel) {
                    if (first >= 0) break else continue
                }
                val w = if (freqMel <= centerMel) {
                    (freqMel - leftMel) / (centerMel - leftMel)
                } else {
                    (rightMel - freqMel) / (rightMel - centerMel)
                }
                if (first < 0) first = i
                weights.add(w)
            }
            (if (first < 0) 0 else first) to weights.toFloatArray()
        }
    }

    /** Frames that [compute] will produce for [numSamples] (snip_edges). */
    fun frameCount(numSamples: Int): Int =
        if (numSamples < frameLength) 0 else 1 + (numSamples - frameLength) / frameShift

    /**
     * Features for [audio] (mono float PCM in [-1, 1]), as `frames x numBins`
     * row-major.
     *
     * Kaldi works in 16-bit units, so the input is scaled by 32768 first: the
     * absolute level changes the log-mel offset, and an encoder trained on
     * kaldi's scale expects kaldi's numbers.
     */
    fun compute(audio: FloatArray): Array<FloatArray> {
        val frames = frameCount(audio.size)
        if (frames == 0) return emptyArray()
        val out = Array(frames) { FloatArray(numBins) }
        val buf = FloatArray(frameLength)
        val spec = FloatArray(fftSize)
        val power = FloatArray(fftSize / 2 + 1)
        for (f in 0 until frames) {
            val start = f * frameShift
            var mean = 0f
            for (i in 0 until frameLength) {
                val v = audio[start + i] * 32768f
                buf[i] = v
                mean += v
            }
            mean /= frameLength
            for (i in 0 until frameLength) buf[i] -= mean
            // Pre-emphasis, walking backwards so each sample still sees its
            // ORIGINAL predecessor; sample 0 uses itself, as kaldi does.
            for (i in frameLength - 1 downTo 1) buf[i] -= preEmphasis * buf[i - 1]
            buf[0] -= preEmphasis * buf[0]
            for (i in 0 until frameLength) spec[i] = buf[i] * window[i]
            java.util.Arrays.fill(spec, frameLength, fftSize, 0f)
            fft.realForward(spec)
            // JTransforms even-n packing: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...]
            power[0] = spec[0] * spec[0]
            power[fftSize / 2] = spec[1] * spec[1]
            for (k in 1 until fftSize / 2) {
                val r = spec[2 * k]
                val i = spec[2 * k + 1]
                power[k] = r * r + i * i
            }
            val row = out[f]
            for (b in 0 until numBins) {
                val (first, weights) = melBanks[b]
                var energy = 0f
                for (k in weights.indices) energy += weights[k] * power[first + k]
                row[b] = ln(energy.coerceAtLeast(FLOOR).toDouble()).toFloat()
            }
        }
        return out
    }

    companion object {
        /** kaldi's own floor: `std::numeric_limits<float>::epsilon()`. */
        private const val FLOOR = 1.1920929e-7f

        /** Sanity helper for tests: RMS of a signal, in kaldi's 16-bit units. */
        fun rms(x: FloatArray): Float =
            sqrt(x.fold(0.0) { a, v -> a + v.toDouble() * v } / x.size).toFloat()
    }
}
