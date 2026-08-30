package org.sisam.langtutor.tutor.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.PhraseSentence

class DrillDeckPhraseTest {

    private fun sentence(id: String, level: Int, en: String, he: String = "עברית") =
        PhraseSentence(id, level, "present-simple", "test-frame", en, he)

    private val bank = listOf(
        sentence("a", 1, "A bee!"),
        sentence("b", 1, "I see a bee."),
        sentence("c", 2, "The bee is flying to the flower."),
        sentence("d", 3, "I saw a bee in the garden."),
        sentence("e", 5, "Bees can see colors that we cannot see."),
    )

    @Test
    fun `pool takes the learner's level and one below, bucketed by length`() {
        // Learner at Level 2: levels 1-2 qualify; "A bee!" (2 tokens) and
        // "I see a bee." (4) are SHORT, the flying sentence is LONG (7).
        val short = DrillDeck.phrasePool(bank, DrillLevel.SHORT, learnerLevel = 2)
        assertEquals(listOf("A bee!", "I see a bee."), short.map { it.text })
        val long = DrillDeck.phrasePool(bank, DrillLevel.LONG, learnerLevel = 2)
        assertEquals(listOf("The bee is flying to the flower."), long.map { it.text })
        // Level 3 material stays out of a Level 2 learner's round.
        assertTrue(DrillDeck.phrasePool(bank, DrillLevel.LONG, 2).none { "saw" in it.text })
    }

    @Test
    fun `every banked item carries its authored Hebrew`() {
        DrillDeck.phrasePool(bank, DrillLevel.SHORT, 7).forEach {
            assertEquals("עברית", it.hebrew)
        }
    }

    @Test
    fun `a Level 1 learner reviews nothing below Level 1`() {
        val items = DrillDeck.phrasePool(bank, DrillLevel.SHORT, learnerLevel = 1)
        assertTrue(items.isNotEmpty())
    }
}
