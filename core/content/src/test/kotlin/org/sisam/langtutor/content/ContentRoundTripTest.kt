package org.sisam.langtutor.content

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRoundTripTest {

    private val repository = ResourceContentRepository()

    @Test
    fun `index lists all units and each loads`() = runTest {
        val units = repository.listUnits()
        assertEquals(4, units.size)
        assertEquals("unit-001", units.first().id)
        assertEquals("צבעים וצעצועים", units.first().title.he)
        units.forEach { summary ->
            val unit = repository.loadUnit(summary.id)
            assertEquals(summary.id, unit.id)
            assertTrue(unit.activities.isNotEmpty())
        }
    }

    @Test
    fun `sample unit deserializes with polymorphic activities`() = runTest {
        val unit = repository.loadUnit("unit-001")
        assertEquals(1, unit.schemaVersion)
        assertEquals(AgeBand.AGES_4_6, unit.ageBand)
        assertEquals(7, unit.activities.size)
        assertEquals(4, unit.activities.filterIsInstance<Activity.Vocab>().size)
        assertEquals(2, unit.activities.filterIsInstance<Activity.RepeatAfterMe>().size)
        val qa = unit.activities.filterIsInstance<Activity.QuestionAnswer>().single()
        assertTrue("red" in qa.expectedAnswers)
    }

    @Test
    fun `unit survives a serialization round trip`() = runTest {
        val json = ResourceContentRepository.DEFAULT_JSON
        val unit = repository.loadUnit("unit-001")
        val encoded = json.encodeToString(CurriculumUnit.serializer(), unit)
        val decoded = json.decodeFromString(CurriculumUnit.serializer(), encoded)
        assertEquals(unit, decoded)
    }
}
