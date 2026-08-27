package org.sisam.langtutor.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParrotEffectTest {

    private val sr = 24_000

    private fun sine(hz: Double, seconds: Double): FloatArray =
        FloatArray((sr * seconds).toInt()) { i -> (0.4 * sin(2 * PI * hz * i / sr)).toFloat() }

    private fun zeroCrossings(audio: FloatArray): Int {
        var n = 0
        for (i in 1 until audio.size) if (audio[i - 1] * audio[i] < 0f) n++
        return n
    }

    @Test
    fun `resample raises pitch by exactly the factor it shortens by`() {
        val tone = sine(440.0, 1.0)
        val shifted = ParrotEffect.resample(tone, ParrotEffect.PITCH)

        assertEquals((tone.size / ParrotEffect.PITCH).toInt(), shifted.size)
        // Pitch is zero-crossings per second; duration changed too, so
        // compare RATES, not counts.
        val toneHz = zeroCrossings(tone) / (tone.size / sr.toFloat())
        val shiftedHz = zeroCrossings(shifted) / (shifted.size / sr.toFloat())
        assertEquals(toneHz * ParrotEffect.PITCH, shiftedHz, toneHz * 0.03f)
    }

    @Test
    fun `warble flutters without clipping and without changing length`() {
        val tone = sine(300.0, 0.5)
        val warbled = ParrotEffect.warble(tone, sr, ParrotEffect.WARBLE_HZ, ParrotEffect.WARBLE_DEPTH)

        assertEquals(tone.size, warbled.size)
        val peak = warbled.maxOf { abs(it) }
        assertTrue("peak $peak exceeds the modulation bound", peak <= 0.4f * (1f + ParrotEffect.WARBLE_DEPTH) + 1e-4f)
        assertTrue(warbled.all { it.isFinite() })
    }

    @Test
    fun `the full treatment keeps the signal finite and the duration honest`() {
        // The engine pre-lengthens by PITCH at synthesis; apply() shortens
        // back, so end-to-end duration lands where the caller asked.
        val preLengthened = sine(200.0, 1.0 * ParrotEffect.PITCH)
        val out = ParrotEffect.apply(preLengthened, sr)

        assertEquals(sr.toFloat(), out.size.toFloat(), sr * 0.02f)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `the flourish is short, soft, and clickless at both ends`() {
        val brrp = ParrotEffect.flourish(sr)
        assertTrue(brrp.size in (sr / 10)..(sr / 4))
        assertTrue(brrp.all { it.isFinite() })
        assertTrue("too loud for an announcement", brrp.maxOf { abs(it) } <= 0.3f)
        // Half-sine envelope: silent edges are what prevents the click.
        assertEquals(0f, brrp.first(), 1e-3f)
        assertEquals(0f, abs(brrp.last()), 0.02f)
    }

    @Test
    fun `kiki's register sits measurably above tuki's`() {
        // The whole point of the second pitch: the two parrots must be
        // tellable apart by ear, so the constants must actually differ and
        // the effect must actually track the parameter.
        assertTrue(ParrotEffect.KIKI_PITCH > ParrotEffect.PITCH)
        val tone = sine(300.0, 1.0)
        val tuki = ParrotEffect.apply(tone, sr, ParrotEffect.PITCH)
        val kiki = ParrotEffect.apply(tone, sr, ParrotEffect.KIKI_PITCH)
        val tukiHz = zeroCrossings(tuki) / (tuki.size / sr.toFloat())
        val kikiHz = zeroCrossings(kiki) / (kiki.size / sr.toFloat())
        assertEquals(
            ParrotEffect.KIKI_PITCH / ParrotEffect.PITCH,
            kikiHz / tukiHz,
            0.05f,
        )
    }

    @Test
    fun `empty audio passes through untouched`() {
        assertEquals(0, ParrotEffect.apply(FloatArray(0), sr).size)
    }
}
