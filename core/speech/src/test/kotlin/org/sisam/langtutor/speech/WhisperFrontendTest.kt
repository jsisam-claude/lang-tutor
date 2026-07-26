package org.sisam.langtutor.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperFrontendTest {

    /** The exact deterministic signal the golden vectors were generated from. */
    private fun testSignal(): FloatArray = FloatArray(WhisperFrontend.SAMPLE_RATE) { n ->
        val t = n.toDouble() / WhisperFrontend.SAMPLE_RATE
        (0.5 * sin(2 * PI * 440 * t) + 0.25 * sin(2 * PI * 1000 * t)).toFloat()
    }

    @Test
    fun `log-mel matches the canonical Whisper reference within tolerance`() {
        val mel = WhisperFrontend.logMel(testSignal())
        assertEquals(WhisperFrontend.N_MELS, mel.size)
        assertEquals(WhisperFrontend.N_FRAMES, mel[0].size)

        val goldens = mapOf(
            0 to WhisperFrontendGolden.FRAME_0,
            1 to WhisperFrontendGolden.FRAME_1,
            50 to WhisperFrontendGolden.FRAME_50,
        )
        var worst = 0.0f
        for ((frame, expected) in goldens) {
            for (m in 0 until WhisperFrontend.N_MELS) {
                val diff = abs(mel[m][frame] - expected[m])
                if (diff > worst) worst = diff
            }
        }
        assertTrue("worst per-bin deviation $worst exceeds 2e-3", worst <= 2e-3f)

        var globalMax = Float.NEGATIVE_INFINITY
        var globalMin = Float.POSITIVE_INFINITY
        for (m in 0 until WhisperFrontend.N_MELS) for (t in 0 until WhisperFrontend.N_FRAMES) {
            if (mel[m][t] > globalMax) globalMax = mel[m][t]
            if (mel[m][t] < globalMin) globalMin = mel[m][t]
        }
        assertEquals(WhisperFrontendGolden.GLOBAL_MAX, globalMax, 2e-3f)
        assertEquals(WhisperFrontendGolden.GLOBAL_MIN, globalMin, 2e-3f)
        // Whisper's normalization pins min to exactly max - 2 (i.e. (max-8+4)/4).
        assertEquals(globalMax - 2.0f, globalMin, 1e-4f)
    }

    @Test
    fun `silence normalizes to a flat floor`() {
        val mel = WhisperFrontend.logMel(FloatArray(WhisperFrontend.SAMPLE_RATE))
        val first = mel[0][0]
        for (m in 0 until WhisperFrontend.N_MELS step 7) {
            for (t in 0 until WhisperFrontend.N_FRAMES step 977) {
                assertEquals(first, mel[m][t], 1e-4f)
            }
        }
    }
}
