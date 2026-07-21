package org.sisam.langtutor.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BktModelTest {

    private val bkt = BktModel()

    @Test
    fun `consecutive correct answers reach mastery`() {
        var state = SkillState()
        repeat(8) { state = bkt.update(state, correct = true) }
        assertTrue(state.pKnown > 0.95)
        assertTrue(bkt.isMastered(state))
        assertEquals(8, state.attempts)
    }

    @Test
    fun `correct raises and incorrect lowers the estimate`() {
        val start = SkillState(pKnown = 0.5)
        val afterCorrect = bkt.update(start, correct = true)
        val afterWrong = bkt.update(start, correct = false)
        assertTrue(afterCorrect.pKnown > start.pKnown)
        assertTrue(afterWrong.pKnown < afterCorrect.pKnown)
    }

    @Test
    fun `estimates stay within probability bounds`() {
        var state = SkillState(pKnown = 0.99)
        repeat(20) { state = bkt.update(state, correct = false) }
        assertTrue(state.pKnown in 0.0..1.0)
        assertFalse(bkt.isMastered(state))

        var up = SkillState(pKnown = 0.01)
        repeat(50) { up = bkt.update(up, correct = true) }
        assertTrue(up.pKnown in 0.0..1.0)
    }

    @Test
    fun `skills survive profile serialization round trip`() {
        val json = kotlinx.serialization.json.Json
        val profile = LearnerProfile(
            childName = "Noa",
            skills = mapOf("phoneme:th" to SkillState(pKnown = 0.42, attempts = 3)),
        )
        val decoded = json.decodeFromString(
            LearnerProfile.serializer(),
            json.encodeToString(LearnerProfile.serializer(), profile),
        )
        assertEquals(profile, decoded)
    }
}
