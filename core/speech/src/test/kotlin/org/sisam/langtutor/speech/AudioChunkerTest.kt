package org.sisam.langtutor.speech

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkerTest {

    private val sr = WhisperFrontend.SAMPLE_RATE
    private val window = 1000 * WhisperFrontend.HOP // the 10 s ACFT export

    @Test
    fun `audio inside the window is returned untouched`() {
        val pcm = FloatArray(window - 1) { 0.1f }
        val pieces = AudioChunker.split(pcm, window)

        assertEquals(1, pieces.size)
        assertTrue(pieces[0] === pcm)
    }

    @Test
    fun `long audio is covered completely with no gaps or overlap`() {
        // 25 s of tone: without chunking, 15 s of it would never reach the model.
        val pcm = FloatArray(sr * 25) { sin(2 * PI * 220 * it / sr).toFloat() * 0.3f }
        val pieces = AudioChunker.split(pcm, window)

        assertTrue("expected several pieces, got ${pieces.size}", pieces.size >= 3)
        pieces.forEach { assertTrue("piece of ${it.size} exceeds the window", it.size <= window) }
        assertEquals(pcm.size, pieces.sumOf { it.size })

        // Rejoining the pieces must reproduce the original sample for sample.
        val joined = FloatArray(pcm.size)
        var at = 0
        pieces.forEach { it.copyInto(joined, at); at += it.size }
        assertTrue(pcm.contentEquals(joined))
    }

    @Test
    fun `the cut lands in the pause rather than mid-word`() {
        // Speech-like tone with one deliberate silence just before the boundary.
        val pcm = FloatArray(window + sr) { sin(2 * PI * 300 * it / sr).toFloat() * 0.4f }
        val silenceStart = window - (0.6f * sr).toInt()
        val silenceEnd = silenceStart + (0.3f * sr).toInt()
        for (i in silenceStart until silenceEnd) pcm[i] = 0f

        val cut = AudioChunker.split(pcm, window).first().size

        assertTrue(
            "cut at $cut should fall inside the silence $silenceStart..$silenceEnd",
            cut in silenceStart..silenceEnd,
        )
    }

    @Test
    fun `constant audio with no pause still makes progress`() {
        // Nothing stands out as quiet: the chunker must not stall or loop.
        val pcm = FloatArray(window * 2 + 500) { 0.25f }
        val pieces = AudioChunker.split(pcm, window)

        assertEquals(pcm.size, pieces.sumOf { it.size })
        pieces.forEach { assertTrue(it.isNotEmpty() && it.size <= window) }
    }
}
