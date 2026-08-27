package org.sisam.langtutor.ui.reward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a young learner is owed a sticker — derived, so this is the whole rule. */
class StickerMilestonesTest {

    private val step = StickerMilestones.XP_PER_STICKER

    private val never = StickerMilestones.NEVER_OFFERED

    /** Nothing offered yet this session. */
    private fun owedFresh(xp: Int, owned: Int) =
        StickerMilestones.owed(xp, owned, lastOfferedOwned = never, lastOfferedEarned = 0)

    @Test
    fun `nothing is owed before the first milestone`() {
        assertFalse(owedFresh(xp = step - 1, owned = 0))
    }

    @Test
    fun `crossing a milestone owes one`() {
        assertTrue(owedFresh(xp = step, owned = 0))
    }

    @Test
    fun `picking it settles the debt`() {
        assertFalse(StickerMilestones.owed(step, owned = 1, lastOfferedOwned = 0, lastOfferedEarned = 1))
    }

    @Test
    fun `three milestones earned at once are three separate trips`() {
        // Not a hypothetical: a long session lands a lot of XP, and an earlier
        // version collapsed the lot into ONE trip because it recorded only the
        // highest milestone offered. Each pick must re-arm the next.
        var owned = 0
        var offeredOwned = never
        var offeredEarned = 0
        var trips = 0
        repeat(10) {
            if (!StickerMilestones.owed(step * 3, owned, offeredOwned, offeredEarned)) return@repeat
            offeredOwned = owned
            offeredEarned = StickerMilestones.earned(step * 3)
            trips++
            owned++ // the learner picks one
        }
        assertEquals(3, trips)
    }

    @Test
    fun `backing out of the room does not trap the learner in it`() {
        // Offered but not picked: the collection is unchanged, so the same
        // state must not re-open the room.
        assertFalse(StickerMilestones.owed(step, owned = 0, lastOfferedOwned = 0, lastOfferedEarned = 1))
    }

    @Test
    fun `after backing out, the next milestone asks again`() {
        assertTrue(StickerMilestones.owed(step * 2, owned = 0, lastOfferedOwned = 0, lastOfferedEarned = 1))
    }
}
