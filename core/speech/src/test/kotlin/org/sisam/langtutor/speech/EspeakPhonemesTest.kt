package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EspeakPhonemesTest {

    private val phonemizer = KokoroPhonemizer.load()

    private fun target(word: String) =
        EspeakPhonemes.expectedFrom(phonemizer.phonemizeToIpa(word)).map { it.label }

    @Test
    fun `stressed FLEECE and GOOSE ask for the long vowel`() {
        // ARPABET writes one symbol where the coach model keeps two apart: IY
        // is both FLEECE and happY, UW both GOOSE and its unstressed mate.
        // Asking for the short one every time scored the ship/sheep drill
        // against the wrong sound.
        assertEquals(listOf("ʃ", "iː", "p"), target("sheep"))
        assertEquals(listOf("b", "l", "uː"), target("blue"))
    }

    @Test
    fun `an unstressed one still asks for the short vowel`() {
        assertEquals("i", target("happy").last())
        assertEquals("i", target("city").last())
    }

    @Test
    fun `the drill the twister room exists for is not scored backwards`() {
        assertNotEquals(target("ship"), target("sheep"))
        assertEquals("ɪ", target("ship")[1])
        assertEquals("iː", target("sheep")[1])
    }

    @Test
    fun `THOUGHT asks for the long vowel the model actually has`() {
        // The KDoc's own example was unreachable: the candidate list had no
        // entry for ɔː, so "ball" was scored against a vowel the model does
        // not emit for it.
        assertEquals(listOf("b", "ɔː", "l"), target("ball"))
    }

    @Test
    fun `an r-coloured vowel is one phone, not two`() {
        // Our front end writes a vowel plus a separate r because misaki does;
        // the model's vocabulary carries the combination as a single token,
        // and asking for two where it produces one puts the alignment out for
        // the rest of the word.
        assertEquals(listOf("k", "ɑːɹ"), target("car"))
        assertEquals(listOf("f", "ɔːɹ"), target("four"))
        assertEquals(listOf("ɪɹ"), target("ear"))
        assertEquals(listOf("ɛɹ"), target("air"))
    }

    @Test
    fun `a syllabic l is one phone too`() {
        assertEquals(listOf("l", "ɪ", "t", "əl"), target("little"))
    }

    @Test
    fun `an ordinary r is still its own phone`() {
        assertEquals(listOf("ɹ", "ɛ", "d"), target("red"))
        assertEquals(listOf("t", "ɹ", "iː"), target("tree"))
    }
}
