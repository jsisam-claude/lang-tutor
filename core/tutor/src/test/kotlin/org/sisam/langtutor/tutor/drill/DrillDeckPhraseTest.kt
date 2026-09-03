package org.sisam.langtutor.tutor.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.PhraseSentence

class DrillDeckPhraseTest {

    private fun sentence(
        id: String,
        level: Int,
        en: String,
        he: String = "עברית",
        theme: String = "",
    ) = PhraseSentence(id, level, "present-simple", "test-frame", en, he, theme = theme)

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

    @Test
    fun `a chosen topic narrows the pool to that topic`() {
        val mixed = listOf(
            sentence("f1", 2, "The cow is big.", theme = "farm-animals"),
            sentence("f2", 2, "I feed the hens.", theme = "farm-animals"),
            sentence("z1", 2, "The lion is loud.", theme = "zoo-animals"),
        )
        val farm = DrillDeck.phrasePool(mixed, DrillLevel.SHORT, learnerLevel = 2, theme = "farm-animals")
        assertEquals(listOf("The cow is big.", "I feed the hens."), farm.map { it.text })
        // Null is still the whole bank, which is what every existing caller
        // relies on.
        assertEquals(3, DrillDeck.phrasePool(mixed, DrillLevel.SHORT, learnerLevel = 2).size)
    }

    @Test
    fun `a topic with nothing at this level gives an empty round, not someone else's lines`() {
        val mixed = listOf(
            sentence("f1", 7, "The cow has been grazing all morning.", theme = "farm-animals"),
            sentence("z1", 2, "The lion is loud.", theme = "zoo-animals"),
        )
        val farm = DrillDeck.phrasePool(mixed, DrillLevel.SHORT, learnerLevel = 2, theme = "farm-animals")
        assertEquals(emptyList<DrillItem>(), farm)
    }
}
