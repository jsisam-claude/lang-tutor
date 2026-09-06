package org.sisam.langtutor.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker is the only writer of [LearnerProfile.skills], so what it
 * promises is worth pinning: that evidence accumulates in the direction the
 * answers point, that a skill nobody has met sorts ahead of every skill they
 * have, and that one answer is never enough to report on.
 */
class SkillTrackerTest {

    private val tracker = SkillTracker()

    @Test
    fun `an id is spelled the same way by every room`() {
        assertEquals("word:bee", Skill.word("Bee"))
        assertEquals("sound:th", Skill.sound("th"))
        assertEquals("theme:market", Skill.theme("market"))
        assertEquals("frame:going-to", Skill.frame("going-to"))
        assertEquals("frame", Skill.kindOf(Skill.frame("going-to")))
        assertEquals("going-to", Skill.labelOf(Skill.frame("going-to")))
        // A word with no namespace is nobody's skill, and says so.
        assertEquals("", Skill.kindOf("bee"))
    }

    @Test
    fun `right answers raise a skill and wrong ones lower it`() {
        var p = LearnerProfile.EMPTY
        val id = Skill.frame("going-to")
        repeat(5) { p = tracker.observe(p, listOf(id), correct = true) }
        val known = tracker.mastery(p, id)
        assertTrue("five right answers left it at $known", known > 0.8)
        repeat(5) { p = tracker.observe(p, listOf(id), correct = false) }
        assertTrue("five wrong answers left it at ${tracker.mastery(p, id)}", tracker.mastery(p, id) < known)
        assertEquals(10, p.skills.getValue(id).attempts)
    }

    @Test
    fun `one answer is evidence about every skill it exercised, once`() {
        val ids = listOf(Skill.theme("market"), Skill.frame("going-to"), Skill.theme("market"))
        val p = tracker.observe(LearnerProfile.EMPTY, ids, correct = true)
        assertEquals(2, p.skills.size)
        // The repeated id counted once: a line does not know its theme twice.
        assertEquals(1, p.skills.getValue(Skill.theme("market")).attempts)
    }

    @Test
    fun `an answer with nothing to attribute changes nothing`() {
        val p = tracker.observe(LearnerProfile.EMPTY, emptyList(), correct = true)
        assertEquals(LearnerProfile.EMPTY, p)
    }

    @Test
    fun `an unseen skill sorts ahead of everything seen`() {
        var p = LearnerProfile.EMPTY
        // Even a skill answered WRONG every time is better known than one
        // never met, so a room drawing weakest-first opens new material.
        repeat(3) { p = tracker.observe(p, listOf(Skill.theme("market")), correct = false) }
        assertTrue(tracker.mastery(p, Skill.theme("market")) > tracker.mastery(p, Skill.theme("zoo")))
        assertEquals(SkillTracker.UNSEEN, tracker.mastery(p, Skill.theme("zoo")), 0.0)
    }

    @Test
    fun `weakest reports one kind, worst first, and only once there is evidence`() {
        var p = LearnerProfile.EMPTY
        repeat(4) { p = tracker.observe(p, listOf(Skill.frame("passive")), correct = false) }
        repeat(4) { p = tracker.observe(p, listOf(Skill.frame("bare-noun")), correct = true) }
        repeat(4) { p = tracker.observe(p, listOf(Skill.word("bee")), correct = false) }
        p = tracker.observe(p, listOf(Skill.frame("if-first")), correct = false)

        val frames = tracker.weakest(p, "frame")
        assertEquals(listOf(Skill.frame("passive"), Skill.frame("bare-noun")), frames.map { it.first })
        // One attempt is noise, so if-first is not reported at all yet.
        assertTrue(frames.none { it.first == Skill.frame("if-first") })
        // And the word is another kind's business.
        assertEquals(listOf(Skill.word("bee")), tracker.weakest(p, "word").map { it.first })
    }

    @Test
    fun `the map stays small enough to keep in a profile`() {
        // The grain is bounded by the content — themes, frames, sounds and
        // words with a picture — so a profile cannot grow without limit
        // however long the app is used. Nothing evicts, and nothing needs to.
        var p = LearnerProfile.EMPTY
        repeat(50) { i -> p = tracker.observe(p, listOf(Skill.frame("f$i")), correct = i % 2 == 0) }
        assertEquals(50, p.skills.size)
        repeat(50) { i -> p = tracker.observe(p, listOf(Skill.frame("f$i")), correct = true) }
        assertEquals("re-answering must not add entries", 50, p.skills.size)
    }
}
