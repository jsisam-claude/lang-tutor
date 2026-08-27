package org.sisam.langtutor.tutor.drill

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.content.ResourceContentRepository

/** The deck is DERIVED from the real curriculum, so test it against the real
 *  curriculum: every unit that ships feeds these pools. */
class DrillDeckTest {

    private suspend fun allUnits(): List<CurriculumUnit> {
        val repo = ResourceContentRepository()
        return repo.listUnits().mapNotNull { repo.loadUnit(it.id) }
    }

    @Test
    fun `every level has a real pool to draw from`() = runTest {
        val units = allUnits()
        for (level in DrillLevel.entries) {
            val pool = DrillDeck.pool(units, level)
            assertTrue("$level pool is empty", pool.size >= DrillDeck.sizeFor(level).coerceAtMost(4))
        }
    }

    @Test
    fun `items land in the bucket their word count says`() = runTest {
        for (level in DrillLevel.entries) {
            for (item in DrillDeck.pool(allUnits(), level)) {
                val words = WordMatch.tokens(item.text).size
                val ok = when (level) {
                    DrillLevel.WORDS -> words == 1
                    DrillLevel.SHORT -> words in 2..4
                    DrillLevel.LONG -> words >= 5
                }
                assertTrue("'${item.text}' ($words words) in $level", ok)
            }
        }
    }

    @Test
    fun `the pool never repeats an item`() = runTest {
        // "red" the vocab word and "Red!" an expected answer are one item.
        for (level in DrillLevel.entries) {
            val keys = DrillDeck.pool(allUnits(), level).map { WordMatch.tokens(it.text) }
            assertEquals(keys.toSet().size, keys.size)
        }
    }

    @Test
    fun `a round is bounded and single-level`() = runTest {
        val units = allUnits()
        val round = DrillDeck.round(units, DrillLevel.WORDS, Random(7))
        assertTrue(round.isNotEmpty())
        assertTrue(round.size <= DrillDeck.sizeFor(DrillLevel.WORDS))
        assertTrue(round.all { it.level == DrillLevel.WORDS })
    }

    @Test
    fun `the shuffle is seeded, so tests and reruns are stable`() = runTest {
        val units = allUnits()
        assertEquals(
            DrillDeck.round(units, DrillLevel.SHORT, Random(42)),
            DrillDeck.round(units, DrillLevel.SHORT, Random(42)),
        )
    }
}
