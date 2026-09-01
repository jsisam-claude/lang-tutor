package org.sisam.langtutor.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frontend is pinned against kaldi ITSELF, not against my arithmetic.
 *
 * `fbank-golden.json` was produced by `kaldi-native-fbank` — the same library
 * the icefall recipe uses — with the icefall settings, on a deterministic
 * synthetic signal. This matters more than a normal unit test: a frontend
 * that is subtly wrong does not throw, it feeds the encoder plausible-looking
 * garbage and the transcripts quietly get worse.
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
    fun `frame count matches kaldi's snip-edges rule`() {
        val fbank = KaldiFbank()
        assertEquals(expected.size, fbank.frameCount(audio.size))
        assertEquals(expected.size, fbank.compute(audio).size)
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
    fun `short audio yields no frames rather than a partial one`() {
        val fbank = KaldiFbank()
        assertEquals(0, fbank.frameCount(399))
        assertEquals(0, fbank.compute(FloatArray(399)).size)
        assertEquals(1, fbank.frameCount(400))
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
