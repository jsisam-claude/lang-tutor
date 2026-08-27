package org.sisam.langtutor.ui.reward

/**
 * When a young learner is owed a sticker.
 *
 * Deliberately derived rather than stored: stickers EARNED is XP over a
 * threshold, stickers OWNED is the length of the collection, and the learner
 * is owed one whenever the first exceeds the second. There is no counter to
 * keep in sync and nothing to corrupt — a crash mid-celebration just means the
 * room opens again next time.
 *
 * Pure so it can be tested; the navigation layer only decides *where* the
 * answer is allowed to interrupt.
 */
object StickerMilestones {

    /**
     * XP per sticker. Ten turns at [org.sisam.langtutor.tutor.TutorOrchestrator.XP_PER_TURN]
     * — a session's worth of work, not a participation trophy.
     */
    const val XP_PER_STICKER = 50

    fun earned(xp: Int): Int = xp / XP_PER_STICKER

    /** No room has been opened yet this session. */
    const val NEVER_OFFERED = -1

    /**
     * Is a sticker owed right now?
     *
     * @param owned how many stickers the learner has actually picked.
     * @param lastOfferedOwned the [owned] value when the room was last opened,
     *   or [NEVER_OFFERED].
     * @param lastOfferedEarned the [earned] value at that same moment.
     *
     * The pair is what makes "declined" distinguishable from "still owed".
     * Tracking only the highest milestone offered was wrong in a way a
     * single-number test could not show: three milestones earned at once got
     * collapsed into ONE trip to the room, because the first trip recorded
     * `earned = 3` and every later check then failed `earned > lastOffered`.
     * Comparing against the COLLECTION as well means picking one re-arms the
     * next, while backing out — which leaves the collection unchanged — does
     * not.
     */
    fun owed(xp: Int, owned: Int, lastOfferedOwned: Int, lastOfferedEarned: Int): Boolean {
        val earned = earned(xp)
        if (earned <= owned) return false
        if (lastOfferedOwned == NEVER_OFFERED) return true
        // Either they took the last one (the collection moved), or they have
        // earned another milestone since we asked.
        return owned != lastOfferedOwned || earned > lastOfferedEarned
    }
}
