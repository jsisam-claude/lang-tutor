package org.sisam.langtutor.tutor.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMatchTest {

    @Test
    fun `exact repetition matches, case and punctuation aside`() {
        assertTrue(WordMatch.matches("I see a red ball!", "i see a red ball"))
        assertTrue(WordMatch.matches("Red!", "RED"))
    }

    @Test
    fun `extra words around the target are free`() {
        // Punishing enthusiasm teaches a learner to say less.
        assertTrue(WordMatch.matches("I see a red ball!", "um I see a red ball yes"))
    }

    @Test
    fun `a one-word item said wrong is wrong`() {
        assertFalse(WordMatch.matches("ball", "tall"))
        assertTrue(WordMatch.matches("ball", "a ball"))
    }

    @Test
    fun `short sentences allow no misses, five words allow one`() {
        assertEquals(0, WordMatch.allowedMisses(3))
        assertEquals(1, WordMatch.allowedMisses(5))
        // "see" missing from a 5-word target: one miss, allowed.
        assertTrue(WordMatch.matches("I see a red ball", "I a red ball"))
        // Two missing: not a repetition any more.
        assertFalse(WordMatch.matches("I see a red ball", "red ball"))
        // 3-word target with a miss fails.
        assertFalse(WordMatch.matches("my hat big", "my hat"))
    }

    @Test
    fun `repeated words must be repeated`() {
        // Multiset, not set: "night night" is not said by one "night".
        assertEquals(1, WordMatch.missing("night night", "night"))
    }

    @Test
    fun `blank input never matches`() {
        assertFalse(WordMatch.matches("ball", ""))
        assertFalse(WordMatch.matches("", "ball"))
    }

    @Test
    fun `apostrophes stay inside their word`() {
        assertTrue(WordMatch.matches("don't stop", "don't stop"))
        assertFalse(WordMatch.matches("don't stop", "dont stop go"))
    }
}
