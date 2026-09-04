package org.sisam.langtutor.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The front end guesses from spelling for any word it cannot look up, and a
 * guess is invisible until someone hears it on a device. These are the words
 * that were caught that way, plus the rule that stops the next one shipping.
 */
class PronunciationExceptionsTest {

    private val phonemizer = KokoroPhonemizer.load()

    private fun say(word: String) = phonemizer.phonemizeToIpa(word)

    @Test
    fun `the tutor says its own name`() {
        // "Hello, I'm Tuki" came out "Taki": the name is not in the CMU
        // dictionary, so RuleG2p guessed the first vowel from the spelling.
        assertEquals("tˈuki", say("Tuki"))
    }

    @Test
    fun `praise is the adjective, not the verb`() {
        // The bundled dictionary carries one pronunciation per word and for
        // this one it kept "to perfect": the room said per-FECT after every
        // correct answer.
        val perfect = say("Perfect")
        assertTrue("stress belongs on the first syllable: $perfect", perfect.startsWith("pˈɜ"))
        assertNotEquals("pɜfˈɛkt", perfect)
    }

    @Test
    fun `the weather noun does not rhyme with find`() {
        val wind = say("wind")
        assertTrue("the weather noun, said ten times in the corpus: $wind", wind.contains("ɪ"))
        assertTrue(!wind.contains("I"))
    }

    @Test
    fun `the verbs are verbs`() {
        assertTrue("use the computer, not 'a use': ${say("use")}", say("use").endsWith("z"))
        assertTrue("close the windows: ${say("close")}", say("close").endsWith("z"))
        assertTrue("I can read, not 'I can red': ${say("read")}", say("read").contains("i"))
    }

    @Test
    fun `a regular possessive is built from its base word`() {
        // Content should not have to enumerate every one it uses.
        assertEquals(say("hamster") + "z", say("hamster's"))
        assertEquals(say("parrot") + "s", say("parrot's"))
        // After a sibilant the possessive takes its own syllable; the front
        // end writes an unstressed IH as a schwa, which is what it is.
        assertEquals(say("horse") + "əz", say("horse's"))
    }

    @Test
    fun `an unknown word still speaks rather than failing`() {
        // RuleG2p remains the safety net for a name nobody listed.
        assertTrue(say("Zamboolik").isNotBlank())
    }
}
