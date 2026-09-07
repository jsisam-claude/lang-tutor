package org.sisam.langtutor.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroPhonemizerTest {

    private val phonemizer = KokoroPhonemizer.load()

    /** The vocabulary's space token — the break Kokoro speaks. */
    private val SPACE_ID = 16

    // Golden ids generated from the SAME committed cmudict/vocab resources by an
    // independent Python mirror, and validated end-to-end: this exact "Hello!"
    // sequence was fed to the real Kokoro q8f16 ONNX model and produced clean
    // 24kHz speech (see task notes). If these break, the front-end drifted.
    @Test
    fun `hello with stress and punctuation matches the golden ids`() {
        assertArrayEquals(intArrayOf(50, 83, 54, 156, 31, 5), phonemizer.phonemize("Hello!"))
    }

    @Test
    fun `a sentence break is a space in the phoneme string`() {
        // The pause between two spoken sentences is carried by the space token,
        // not by the punctuation alone. Without it the drill's recast reached
        // the model as one unbroken run and was spoken as one, which is what a
        // learner heard: "Almostlisten again".
        val ipa = phonemizer.phonemizeToIpa("Almost! Listen again.")
        assertTrue("punctuation must not swallow the separator: $ipa", ipa.contains("! "))
        assertTrue("punctuation binds to the word before it: $ipa", !ipa.contains(" !"))
        // Two words follow a break here: after "!" and after the first space.
        assertEquals(2, phonemizer.phonemize("Almost! Listen again.").count { it == SPACE_ID })
    }

    @Test
    fun `an ellipsis and a dash are pauses, not nothing`() {
        // Both are in the vocabulary (ids 10 and 9) and both were being
        // discarded before encode(), so "Wait… now" reached the model as
        // "Wait now" — bit for bit, and so 10 ms of quiet instead of 90.
        val with = phonemizer.phonemize("Wait… now try.")
        assertTrue("ellipsis token present", with.contains(10))
        assertTrue("it changes the sequence", !with.contentEquals(phonemizer.phonemize("Wait now try.")))
        // The typed forms reach the same token.
        assertArrayEquals(with, phonemizer.phonemize("Wait... now try."))
        assertTrue("dash token present", phonemizer.phonemize("Wait — now.").contains(9))
        assertArrayEquals(phonemizer.phonemize("Wait — now."), phonemizer.phonemize("Wait -- now."))
        // Inside a compound an en dash is a hyphen, not a pause.
        assertTrue("no pause inside well–done", !phonemizer.phonemize("well–done").contains(9))
        assertArrayEquals(phonemizer.phonemize("well-done"), phonemizer.phonemize("well–done"))
    }

    @Test
    fun `ordinals and oversized numerals are spoken, not split or thrown`() {
        assertEquals("first wash your hands", KokoroTextNormalizer.normalize("1st wash your hands"))
        assertEquals("twenty second", KokoroTextNormalizer.normalize("22nd"))
        assertEquals("one hundredth", KokoroTextNormalizer.normalize("100th"))
        assertEquals("third-grade", KokoroTextNormalizer.normalize("3rd-grade"))
        // Too big for a number: read digit by digit rather than thrown at.
        assertEquals("two zero zero zero zero zero zero zero zero zero zero zero zero zero zero zero zero zero zero zero zero", KokoroTextNormalizer.normalize("200000000000000000000"))
    }

    @Test
    fun `a run of punctuation stays one run`() {
        val ipa = phonemizer.phonemizeToIpa("What?! Again.")
        assertTrue("no separator inside a punctuation run: $ipa", ipa.contains("?!"))
        assertTrue("but the next word still breaks: $ipa", ipa.contains("! "))
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
    fun `hyphenated compounds are spoken as their parts`() {
        // "well-done" is not in CMU as a whole; it must sound like the two
        // words joined (no space token between the parts).
        val well = phonemizer.phonemize("well")
        val done = phonemizer.phonemize("done")
        assertArrayEquals(well + done, phonemizer.phonemize("well-done"))
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
