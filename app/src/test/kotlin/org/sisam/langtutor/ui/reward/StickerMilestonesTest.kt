package org.sisam.langtutor.ui.reward

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a young learner is owed a sticker — derived, so this is the whole rule. */
class StickerMilestonesTest {

    private val step = StickerMilestones.XP_PER_STICKER

    @Test
    fun `nothing is owed before the first milestone`() {
        assertFalse(StickerMilestones.owed(xp = step - 1, owned = 0, lastOffered = 0))
    }

    @Test
    fun `crossing a milestone owes one`() {
        assertTrue(StickerMilestones.owed(xp = step, owned = 0, lastOffered = 0))
    }

    @Test
    fun `picking it settles the debt`() {
        assertFalse(StickerMilestones.owed(xp = step, owned = 1, lastOffered = 1))
    }

    @Test
    fun `two milestones earned at once are both owed`() {
        // Not a hypothetical: a session finished offline can land a lot of XP
        // at once. The learner gets both trips, one after the other.
        assertTrue(StickerMilestones.owed(xp = step * 2, owned = 0, lastOffered = 0))
        assertTrue(StickerMilestones.owed(xp = step * 2, owned = 1, lastOffered = 1))
        assertFalse(StickerMilestones.owed(xp = step * 2, owned = 2, lastOffered = 2))
    }

    @Test
    fun `backing out of the room does not trap the learner in it`() {
        // Offered but not picked: owned stays 0, so without the lastOffered
        // guard this would send them straight back in, forever.
        assertFalse(StickerMilestones.owed(xp = step, owned = 0, lastOffered = 1))
        // ...and they are asked again at the next milestone.
        assertTrue(StickerMilestones.owed(xp = step * 2, owned = 0, lastOffered = 1))
    }
}
