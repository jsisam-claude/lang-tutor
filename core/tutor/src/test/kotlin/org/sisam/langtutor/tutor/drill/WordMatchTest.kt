package org.sisam.langtutor.tutor.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.speech.KokoroPhonemizer

class WordMatchTest {

    /** The judge that can hear: the app's own front end. */
    private val sound = Pronunciation.of(KokoroPhonemizer.load())

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

    @Test
    fun `missed word indexes name the exact words, in target order`() {
        assertEquals(
            setOf(1, 3),
            WordMatch.missedWordIndexes("I see a bee", "I a flower"),
        )
        assertEquals(emptySet<Int>(), WordMatch.missedWordIndexes("I see", "um I see yes"))
        // Duplicates are a multiset: saying "the" once covers only one "the".
        assertEquals(
            setOf(3),
            WordMatch.missedWordIndexes("the cat and the dog", "the cat and dog"),
        )
    }

    @Test
    fun `word order counts`() {
        // The whole grammar point of a level-2 question is the inversion, and
        // a bag-of-words count made it free: every word is present either way,
        // so the room called an un-inverted repetition flawless and moved on.
        val target = "Are you playing with the blocks?"
        assertEquals(emptySet<Int>(), WordMatch.missedWordIndexes(target, "are you playing with the blocks"))
        assertEquals(
            "the word that moved is the one to mark",
            setOf(0),
            WordMatch.missedWordIndexes(target, "you are playing with the blocks"),
        )
    }

    @Test
    fun `a repeated word still matches once per occurrence`() {
        assertEquals(emptySet<Int>(), WordMatch.missedWordIndexes("the cat and the dog", "the cat and the dog"))
        assertEquals(setOf(3), WordMatch.missedWordIndexes("the cat and the dog", "the cat and dog"))
    }

    @Test
    fun `the exact matcher accepts only a complete, ordered repetition`() {
        val target = "I see a red ball"
        assertTrue(WordMatch.matchesExactly(target, "i see a red ball"))
        // Forgiving enough to pass the verdict, not enough to end the turn.
        assertTrue(WordMatch.matches(target, "i see a red"))
        assertFalse(WordMatch.matchesExactly(target, "i see a red"))
        assertFalse(WordMatch.matchesExactly(target, "i see red a ball"))
        assertFalse(WordMatch.matchesExactly(target, ""))
    }

    // --- what the recogniser writes versus what the bank writes -------------
    //
    // Measured: every allowance-0 item in the bank, synthesised and run
    // through the shipped Whisper — 30 of 324 trials rejected a perfect
    // utterance, and all of them were spelling.

    @Test
    fun `the recogniser writes numerals and the bank writes words`() {
        assertTrue(WordMatch.matches("Ten fingers.", "10 fingers"))
        assertTrue(WordMatch.matches("Five eggs.", "5 eggs"))
        assertTrue(WordMatch.matchesExactly("Ten fingers.", "10 fingers"))
        assertEquals(emptySet<Int>(), WordMatch.missedWordIndexes("Ten fingers.", "10 fingers"))
        // A different number is a different answer.
        assertFalse(WordMatch.matches("Two crabs.", "3 crabs"))
        assertEquals(setOf(0), WordMatch.missedWordIndexes("Two crabs.", "3 crabs"))
    }

    @Test
    fun `a numeral in the target keeps its place on the screen`() {
        // Indexes name whitespace words; a "3" that becomes "three" is still
        // one word at the same index.
        assertEquals(setOf(1), WordMatch.missedWordIndexes("3 red cats", "three cats"))
    }

    @Test
    fun `two spellings of one sound are one word`() {
        assertTrue(WordMatch.matches("Are you OK?", "Are you okay?", sound))
        assertTrue(WordMatch.matchesExactly("Are you OK?", "are you okay", sound))
        assertEquals(emptySet<Int>(), WordMatch.missedWordIndexes("Are you OK?", "are you okay", sound))
        assertTrue(WordMatch.matches("I see cereal.", "I see serial.", sound))
        // Spelling alone still cannot hear it — the default judge is unchanged.
        assertFalse(WordMatch.matches("Are you OK?", "Are you okay?"))
    }

    @Test
    fun `sounding the same is exact, not close`() {
        // Every one of these is a contrast the twisters room exists to teach.
        assertFalse(WordMatch.matches("ball", "tall", sound))
        assertFalse(WordMatch.matches("Three sheep.", "three ship", sound))
        assertFalse(WordMatch.matches("Three thin things.", "tree thin things", sound))
        assertFalse(WordMatch.matches("Sing and swing!", "sin and swing", sound))
        assertEquals(setOf(0), WordMatch.missedWordIndexes("Three sheep.", "tree sheep", sound))
    }

    @Test
    fun `one word written as two, or two as one`() {
        // The recogniser's spacing is not the learner's pronunciation.
        assertTrue(WordMatch.matches("Have you said good night to Grandpa?", "Have you said goodnight to Grandpa?"))
        assertTrue(WordMatch.matchesExactly("good night", "goodnight"))
        assertTrue(WordMatch.matches("Three pinecones.", "Three pine cones.", sound))
        assertTrue(WordMatch.matches("I see cereal.", "Icy cereal.", sound))
        assertEquals(emptySet<Int>(), WordMatch.missedWordIndexes("I see cereal.", "Icy cereal.", sound))
        // Only an exact join: a word that merely starts the same is not said.
        assertFalse(WordMatch.matches("I see cereal.", "Icy", sound))
        assertEquals(setOf(0, 1), WordMatch.missedWordIndexes("I see cereal.", "cereal", sound))
        // And a join cannot be used to say fewer words than the line has.
        assertFalse(WordMatch.matches("a big dog", "a dog"))
    }

    @Test
    fun `hearing does not loosen the allowance`() {
        assertFalse(WordMatch.matches("I see a red ball", "red ball", sound))
        assertFalse(WordMatch.matches("my hat big", "my hat", sound))
    }
}
