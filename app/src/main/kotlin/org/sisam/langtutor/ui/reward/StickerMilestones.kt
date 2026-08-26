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

    /**
     * @param lastOffered the highest milestone already opened this session.
     *   Without it, a learner who backed out of the room would be sent
     *   straight back into it; with it they are simply asked again at the next
     *   milestone.
     */
    fun owed(xp: Int, owned: Int, lastOffered: Int): Boolean {
        val earned = earned(xp)
        return earned > owned && earned > lastOffered
    }
}
