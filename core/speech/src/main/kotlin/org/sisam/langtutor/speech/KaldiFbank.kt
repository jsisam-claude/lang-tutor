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
 * setting the icefall recipe uses, and the test suite pins the pipeline
 * against `kaldi-native-fbank` itself rather than trusting the arithmetic
 * to be obviously right.
 *
 * Three of those settings were wrong for a year, and the golden file was
 * generated with the same wrong three, so the test passed (docs/
 * loop-accuracy.md). The shipped export takes samples in [−1, 1] — icefall
 * trains on lhotse's float waveforms — and this class multiplied by 32768
 * "because kaldi works in 16-bit units". That adds 2·ln(32768) ≈ 20.8 to
 * every log-mel value; the encoder's conv-embed writes SwooshR literally as
 * log(1+exp(x−c)), which overflows, and from the third chunk every frame is
 * NaN and the transducer emits only blank. Established with sherpa-onnx on
 * the same three .onnx files (it decodes; this did not) and with probe
 * models reading the encoder's own per-chunk input back out. The other two
 * — `snip_edges=false` and `high_freq=−400` — were audible only as a
 * slightly different spectrum, but they are the recipe's, and sherpa's
 * numbers match to the last digit only with all three.
 *
 * The defaults, all deliberate:
 * - 80 mel bins, 16 kHz, 25 ms frame (400 samples), 10 ms shift (160).
 * - Samples as given, in [−1, 1]. No 16-bit scaling.
 * - `dither = 0`: training dithers for robustness, inference must not, or the
 *   same audio decodes differently twice.
 * - `remove_dc_offset`: each frame has its own mean subtracted, before
 *   pre-emphasis.
 * - Pre-emphasis 0.97, with the first sample using itself as its predecessor
 *   (kaldi's edge convention, not zero).
 * - Povey window: a Hann raised to 0.85 — kaldi's own, and NOT interchangeable
 *   with Hann.
 * - `snip_edges = false`: frames are centred on multiples of the shift, so
 *   there are `(n + 80) / 160` of them and the first and last reach past the
 *   audio, where kaldi mirrors the samples back. Online, a frame exists once
 *   its whole window does ([frameCount] with `flush = false`); the frames
 *   that straddle the end are produced when the stream is finished.
 * - Mel range 20 Hz to Nyquist − 400 Hz.
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
    /** ≤ 0 means Nyquist plus this. */
    highFreq: Float = -400f,
    private val preEmphasis: Float = 0.97f,
    /** kaldi's own frame rule: no frame reaches past the audio. The recipe
     *  this encoder came from does not use it. */
    private val snipEdges: Boolean = false,
    /** Multiplier on the input. 1 for a [−1, 1] waveform, which is what the
     *  shipped export takes; 32768 would be kaldi's 16-bit convention. */
    private val sampleScale: Float = 1f,
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

    /**
     * Frames [compute] will produce for [numSamples]. With [flush] false —
     * the online rule — only frames whose whole window is already inside
     * the audio, so a stream never has to revise a frame it has emitted.
     */
    fun frameCount(numSamples: Int, flush: Boolean = true): Int {
        if (snipEdges) return if (numSamples < frameLength) 0 else 1 + (numSamples - frameLength) / frameShift
        var n = (numSamples + frameShift / 2) / frameShift
        if (!flush) while (n > 0 && firstSample(n - 1) + frameLength > numSamples) n--
        return n.coerceAtLeast(0)
    }

    /** Where frame [frame]'s window begins; negative before the audio when
     *  edges are not snipped. */
    private fun firstSample(frame: Int): Int =
        if (snipEdges) frame * frameShift else frame * frameShift + frameShift / 2 - frameLength / 2

    /**
     * Features for frames [[firstFrame], [firstFrame] + [frames]) of [audio]
     * (mono float PCM in [−1, 1]), as `frames x numBins` row-major. The
     * frames are positioned against the WHOLE of [audio] — a stream passes
     * its full buffer and the range it has not emitted yet — because with
     * edges not snipped the first window reaches before sample 0 and is
     * mirrored, which a copied span would get wrong.
     */
    fun compute(
        audio: FloatArray,
        firstFrame: Int = 0,
        frames: Int = frameCount(audio.size),
    ): Array<FloatArray> {
        if (frames <= 0) return emptyArray()
        val n = audio.size
        val out = Array(frames) { FloatArray(numBins) }
        val buf = FloatArray(frameLength)
        val spec = FloatArray(fftSize)
        val power = FloatArray(fftSize / 2 + 1)
        for (f in 0 until frames) {
            val start = firstSample(firstFrame + f)
            var mean = 0f
            for (i in 0 until frameLength) {
                // kaldi ExtractWindow: an index past either edge is mirrored
                // back in (−1 → 0, n → n−1), as often as it takes.
                var s = start + i
                while (s < 0 || s >= n) s = if (s < 0) -s - 1 else 2 * n - 1 - s
                val v = audio[s] * sampleScale
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
