package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accent is a rewrite of the phoneme string, so it is testable without a
 * speaker: what must hold is that it changes the sounds it claims to change,
 * that every sound it produces is one both models actually have, and that it
 * changes nothing else.
 */
class PhonologyTest {

    private val phonemizer = KokoroPhonemizer.load()

    @Test
    fun `general american is the identity`() {
        val ipa = phonemizer.phonemizeToIpa("Take care of my gold.")
        assertEquals(ipa, Phonology.GENERAL_AMERICAN.applyTo(ipa))
    }

    @Test
    fun `the scottish rewrite makes the four substitutions it documents`() {
        val ipa = phonemizer.phonemizeToIpa("Take care of my gold.")
        val scots = Phonology.SCOTTISH.applyTo(ipa)
        assertTrue("FACE should be a monophthong: $scots", 'A' !in scots && 'e' in scots)
        assertTrue("GOAT should be a monophthong: $scots", 'O' !in scots && 'o' in scots)
        assertTrue("the r should be tapped: $scots", 'ɹ' !in scots && 'ɾ' in scots)
        assertEquals("tˈek kˈɛɾ ˈʌv mˈI ɡˈold.", scots)
    }

    @Test
    fun `cot and caught merge`() {
        val scots = Phonology.SCOTTISH.applyTo(phonemizer.phonemizeToIpa("I bought a small ball."))
        assertTrue("ɔ should have merged into ɒ: $scots", 'ɔ' !in scots)
    }

    @Test
    fun `the accent never leaves the vocabulary Kokoro can say`() {
        // encode() drops what it does not recognise SILENTLY, so an accent
        // that emitted an off-vocabulary symbol would not fail — it would
        // quietly delete a sound from the middle of a word.
        for (line in LINES) {
            val scots = Phonology.SCOTTISH.applyTo(phonemizer.phonemizeToIpa(line))
            assertEquals("$line: ${phonemizer.unsupported(scots)}", emptySet<Char>(), phonemizer.unsupported(scots))
        }
    }

    @Test
    fun `the accent never leaves the vocabulary the coach can score`() {
        // If the coach cannot score a phone the voice just said, the child is
        // graded against a shorter sentence than they heard.
        for (line in LINES) {
            val plain = phonemizer.phonemizeToIpa(line)
            val scots = Phonology.SCOTTISH.applyTo(plain)
            assertEquals(
                "$line: the coach lost a phone in the accent",
                EspeakPhonemes.expectedFrom(plain).size,
                EspeakPhonemes.expectedFrom(scots).size,
            )
        }
    }

    @Test
    fun `the token count never moves`() {
        // The style row is indexed by token count and the karaoke timings are
        // shares of it, so a substitution that changed length would retune the
        // voice and slide the highlight.
        for (line in LINES) {
            assertEquals(
                line,
                phonemizer.phonemize(line).size,
                phonemizer.phonemize(line, Phonology.SCOTTISH).size,
            )
        }
    }

    @Test
    fun `a front end with no accents ignores the request rather than failing`() {
        val plain = KokoroFrontEnd { intArrayOf(1, 2, 3) }
        assertTrue(plain.phonemize("anything", Phonology.SCOTTISH).contentEquals(intArrayOf(1, 2, 3)))
    }

    private companion object {
        val LINES = listOf(
            "Take care of my gold.",
            "The old boat goes home over the water.",
            "Away and boil your head.",
            "Three thin things.",
            "I bought a small ball.",
            "Peter picks pink peppers.",
        )
    }
}
