package org.sisam.langtutor.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frontend is pinned against kaldi ITSELF, not against my arithmetic.
 *
 * `fbank-golden.json` was produced by `kaldi-native-fbank` 1.22 — the same
 * library the icefall recipe and sherpa-onnx use — with the recipe's own
 * settings (`snip_edges=false`, `high_freq=-400`, dither off, the waveform in
 * [-1, 1] as given), on a deterministic synthetic signal. This matters more
 * than a normal unit test: a frontend that is subtly wrong does not throw,
 * it feeds the encoder plausible-looking garbage and the transcripts quietly
 * get worse — or, as it turned out, it feeds it +20.8 on every value and the
 * transcripts quietly vanish (docs/loop-accuracy.md). The previous golden
 * had been generated with the same three wrong settings as the code, which
 * is how it passed for a year; the settings are recorded in the file now.
 */
class KaldiFbankTest {

    private val golden = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fbank-golden.json"))
            .bufferedReader().readText(),
    )

    private val audio: FloatArray =
        golden.jsonObject["audio"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()

    private val expected: List<FloatArray> =
        golden.jsonObject["feats"]!!.jsonArray.map { row ->
            row.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
        }

    @Test
    fun `frame count matches kaldi's rule, edges not snipped`() {
        val fbank = KaldiFbank()
        // (8000 + 80) / 160 = 50 frames, not the 48 that fit inside.
        assertEquals(50, expected.size)
        assertEquals(expected.size, fbank.frameCount(audio.size))
        assertEquals(expected.size, fbank.compute(audio).size)
    }

    @Test
    fun `online, a frame exists only once its whole window is in`() {
        val fbank = KaldiFbank()
        // Frame 49's window is [7720, 8120): it straddles the end and is a
        // flush-only frame. Frame 48's is [7560, 7960): inside, so online.
        assertEquals(49, fbank.frameCount(audio.size, flush = false))
        assertEquals(0, fbank.frameCount(279, flush = false))
        assertEquals(1, fbank.frameCount(280, flush = false))
        // The online frames are the same frames, not approximations of them.
        val online = fbank.compute(audio, 0, fbank.frameCount(audio.size, flush = false))
        val all = fbank.compute(audio)
        for (f in online.indices) assertArrayEquals("frame $f", all[f], online[f], 0f)
    }

    @Test
    fun `a stream that computes as audio arrives gets the same frames as one shot`() {
        // What ZipformerStreamingAsr.Stream does: the whole buffer so far,
        // the frames not yet emitted, the online rule; then a flush.
        val fbank = KaldiFbank()
        val chunk = 2048
        val frames = ArrayList<FloatArray>()
        var next = 0
        var have = 0
        while (have < audio.size) {
            have = minOf(have + chunk, audio.size)
            val buffer = audio.copyOf(have)
            val ready = fbank.frameCount(buffer.size, flush = false)
            if (ready > next) {
                frames.addAll(fbank.compute(buffer, next, ready - next))
                next = ready
            }
        }
        val all = fbank.frameCount(audio.size)
        if (all > next) frames.addAll(fbank.compute(audio, next, all - next))
        assertEquals(expected.size, frames.size)
        for (f in expected.indices) {
            for (b in expected[f].indices) assertEquals("frame $f bin $b", expected[f][b], frames[f][b], 0.005f)
        }
    }

    @Test
    fun `every mel bin matches the reference implementation`() {
        val actual = KaldiFbank().compute(audio)
        assertEquals(expected.size, actual.size)
        // Asserted PER BIN, not folded into a running maximum: `d > worst` is
        // false for NaN, so a max-fold would have let a NaN through silently —
        // the one failure this test most needs to catch. The delta overload
        // fails on NaN.
        for (f in expected.indices) {
            assertEquals("bins in frame $f", expected[f].size, actual[f].size)
            for (b in expected[f].indices) {
                // Tolerance covers float32 rounding and the golden file's 4
                // decimals, nothing structural.
                assertEquals("frame $f bin $b", expected[f][b], actual[f][b], 0.005f)
            }
        }
    }

    @Test
    fun `short audio still frames, mirrored at the edges`() {
        val fbank = KaldiFbank()
        assertEquals(0, fbank.frameCount(0))
        assertEquals(0, fbank.compute(FloatArray(0)).size)
        // An explicit count over nothing has nothing to mirror against.
        assertEquals(0, fbank.compute(FloatArray(0), 0, 1).size)
        // (80 + 80) / 160: one frame, its window mostly mirrored audio.
        assertEquals(1, fbank.frameCount(80))
        assertEquals(1, fbank.compute(FloatArray(80) { 0.1f }).size)
        // (399 + 80) / 160 = 2.
        assertEquals(2, fbank.frameCount(399))
    }

    @Test
    fun `the old contract is still available, explicitly`() {
        // Not what the shipped encoder wants; kept so the difference is a
        // parameter and not folklore.
        val kaldi16 = KaldiFbank(snipEdges = true, sampleScale = 32768f, highFreq = 0f)
        assertEquals(48, kaldi16.frameCount(audio.size))
        assertEquals(0, kaldi16.frameCount(399))
        // 2·ln(32768) higher on a loud frame, as the scale predicts.
        val a = KaldiFbank(snipEdges = true, highFreq = 0f).compute(audio)[20]
        val b = kaldi16.compute(audio)[20]
        assertEquals(20.79f, b[40] - a[40], 0.05f)
    }

    @Test
    fun `digital silence floors instead of returning negative infinity`() {
        val row = KaldiFbank().compute(FloatArray(4000))[0]
        assertTrue(row.all { it.isFinite() })
        // kaldi floors at FLT_EPSILON, so silence is ln(1.19e-7) = -15.9 exactly.
        assertEquals(-15.94f, row[0], 0.01f)
    }

    @Test
    fun `frame geometry is the 25ms over 10ms the encoder was trained on`() {
        val fbank = KaldiFbank()
        assertEquals(400, fbank.frameLength)
        assertEquals(160, fbank.frameShift)
    }
}
