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
}
