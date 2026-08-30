package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeTimingTest {

    @Test
    fun `spans preserve offsets and attached punctuation`() {
        assertEquals(
            listOf(0 to 1, 2 to 5, 6 to 7, 8 to 12),
            KaraokeTiming.wordSpans("I see a bee."),
        )
        assertEquals(emptyList<Pair<Int, Int>>(), KaraokeTiming.wordSpans("   "))
    }

    @Test
    fun `frames split by phoneme share and the first word starts at zero`() {
        // Counts 1, 3, 1, 3 over 800 frames: starts at 0, 100, 400, 500.
        val counts = mapOf("I" to 1, "see" to 3, "a" to 1, "bee." to 3)
        val words = KaraokeTiming.of("I see a bee.", { counts.getValue(it) }, 800)
        assertEquals(listOf(0, 100, 400, 500), words.map { it.startFrame })
        assertEquals(0, words.first().startFrame)
        // Monotonic — a later word can never start before an earlier one.
        assertTrue(words.zipWithNext().all { (a, b) -> a.startFrame <= b.startFrame })
    }

    @Test
    fun `an unvoiceable token gets a floor weight instead of vanishing`() {
        // The dash phonemizes to nothing; with a floor of 1 it still owns a
        // sliver of time and the arithmetic never divides by zero.
        val words = KaraokeTiming.of("go — stop", { if (it == "—") 0 else 2 }, 500)
        assertEquals(3, words.size)
        assertEquals(0, words[0].startFrame)
        assertTrue(words[1].startFrame < words[2].startFrame)
    }

    @Test
    fun `empty audio or text produces nothing`() {
        assertTrue(KaraokeTiming.of("hi", { 1 }, 0).isEmpty())
        assertTrue(KaraokeTiming.of("", { 1 }, 100).isEmpty())
    }

    @Test
    fun `a throwing phoneme counter degrades to the floor, not a crash`() {
        val words = KaraokeTiming.of("a b", { error("boom") }, 100)
        assertEquals(listOf(0, 50), words.map { it.startFrame })
    }
}
