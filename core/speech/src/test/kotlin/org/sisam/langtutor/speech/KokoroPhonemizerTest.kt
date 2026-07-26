package org.sisam.langtutor.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroPhonemizerTest {

    private val phonemizer = KokoroPhonemizer.load()

    // Golden ids generated from the SAME committed cmudict/vocab resources by an
    // independent Python mirror, and validated end-to-end: this exact "Hello!"
    // sequence was fed to the real Kokoro q8f16 ONNX model and produced clean
    // 24kHz speech (see task notes). If these break, the front-end drifted.
    @Test
    fun `hello with stress and punctuation matches the golden ids`() {
        assertArrayEquals(intArrayOf(50, 83, 54, 156, 31, 5), phonemizer.phonemize("Hello!"))
    }

    @Test
    fun `words are space-separated and stressed like the golden`() {
        assertArrayEquals(
            intArrayOf(53, 156, 72, 56, 16, 52, 156, 63, 16, 61, 156, 24, 16, 123, 156, 86, 46, 6),
            phonemizer.phonemize("Can you say red?"),
        )
    }

    @Test
    fun `normalizer expands digits before phonemization`() {
        // "2" must be SPOKEN ("two"), not dropped: same ids as the written word.
        assertArrayEquals(
            phonemizer.phonemize("I have two cats."),
            phonemizer.phonemize("I have 2 cats."),
        )
    }

    @Test
    fun `out-of-dictionary names are still spoken via rule fallback`() {
        // Hebrew kids' names are never in CMU; they must produce SOMETHING speakable.
        for (name in listOf("Noa", "Yael", "Itai", "Shira")) {
            val ids = phonemizer.phonemize(name)
            assertTrue("$name produced no phonemes", ids.isNotEmpty())
        }
        // And the fallback output is valid ARPABET consumable by the mapper.
        assertEquals("N OW1", RuleG2p.toArpabet("Noa"))
    }

    @Test
    fun `normalizer speaks numbers currency and percent`() {
        assertEquals("I have forty two cats", KokoroTextNormalizer.normalize("I have 42 cats"))
        assertEquals(
            "five dollars and ninety nine cents",
            KokoroTextNormalizer.normalize("$5.99"),
        )
        assertEquals("one hundred percent", KokoroTextNormalizer.normalize("100%"))
        assertEquals("three point one four", KokoroTextNormalizer.normalize("3.14"))
    }
}
