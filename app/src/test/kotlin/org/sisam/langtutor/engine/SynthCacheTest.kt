package org.sisam.langtutor.engine

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SynthCacheTest {

    @Before fun reset() = SynthCache.clear()

    private fun audio(seconds: Float = 1f) = FloatArray((24_000 * seconds).toInt()) { 0.1f }

    @Test
    fun `a line is only served once it has enough variants to vary`() {
        val key = SynthCache.key("Great job!", "af_heart.bin", null, 1f)
        // Serving after ONE rendition would make the app repeat a single
        // waveform exactly, which is what the pace jitter exists to avoid.
        repeat(SynthCache.MAX_VARIANTS - 1) {
            assertNull(SynthCache.get(key))
            SynthCache.put(key, audio())
        }
        assertNull(SynthCache.get(key))
        SynthCache.put(key, audio())
        assertNotNull(SynthCache.get(key))
    }

    @Test
    fun `once warm it really does vary`() {
        val key = SynthCache.key("Well done!", "af_heart.bin", null, 1f)
        repeat(SynthCache.MAX_VARIANTS) { i ->
            SynthCache.put(key, FloatArray(100) { i.toFloat() })
        }
        // Deterministic seed, but across draws we must see more than one.
        val seen = (0 until 40).map { SynthCache.get(key, Random(it))!![0] }.toSet()
        assertTrue("only ever returned $seen", seen.size > 1)
    }

    @Test
    fun `variants stop accumulating at the cap`() {
        val key = SynthCache.key("Perfect!", "af_heart.bin", null, 1f)
        repeat(SynthCache.MAX_VARIANTS * 3) { SynthCache.put(key, audio(0.1f)) }
        assertNotNull(SynthCache.get(key))
    }

    @Test
    fun `everything that changes the sound changes the key`() {
        val base = SynthCache.key("Hello", "af_heart.bin", null, 1f)
        assertFalse(base == SynthCache.key("Hello", "bm_george.bin", null, 1f))
        // The parrot flavor is baked into the cached audio, so pitch must split.
        assertFalse(base == SynthCache.key("Hello", "af_heart.bin", 1.22f, 1f))
        // The drill's slow recast is the same words at 0.75x.
        assertFalse(base == SynthCache.key("Hello", "af_heart.bin", null, 0.75f))
        assertEquals(base, SynthCache.key("Hello", "af_heart.bin", null, 1f))
    }

    @Test
    fun `long lines are not eligible, so replies cannot evict praise`() {
        assertTrue(SynthCache.eligible("Great job!"))
        assertFalse(SynthCache.eligible("x".repeat(SynthCache.MAX_TEXT_CHARS + 1)))
    }

    @Test
    fun `the cache is bounded by samples and evicts oldest first`() {
        // Fill well past capacity; the earliest key must be gone.
        val first = SynthCache.key("first", "v", null, 1f)
        repeat(SynthCache.MAX_VARIANTS) { SynthCache.put(first, audio(5f)) }
        assertNotNull(SynthCache.get(first))
        repeat(6) { i ->
            val k = SynthCache.key("line$i", "v", null, 1f)
            repeat(SynthCache.MAX_VARIANTS) { SynthCache.put(k, audio(5f)) }
        }
        assertNull("oldest entry should have been evicted", SynthCache.get(first))
    }

    @Test
    fun `oversized audio is refused rather than blowing the budget`() {
        val key = SynthCache.key("huge", "v", null, 1f)
        SynthCache.put(key, FloatArray(SynthCache.MAX_SAMPLES + 1))
        repeat(SynthCache.MAX_VARIANTS) { SynthCache.put(key, audio(0.1f)) }
        assertNotNull(SynthCache.get(key))
    }
}
