package org.sisam.langtutor.tutor.drill

import org.sisam.langtutor.speech.PronunciationScore

/**
 * A second opinion on a rejected attempt, from the coach.
 *
 * The verdict is text: the recogniser writes the attempt down and the judge
 * compares words. When the recogniser mishears a word that was said
 * perfectly — "Assured" for *A shirt*, "That is" for *Dad is* — the judge
 * cannot know, and the learner is told to try again. The app has a second
 * model that hears differently: the pronunciation coach force-aligns the
 * TARGET's own sounds against the audio and says how well each one was
 * produced. On the short-item census, the attempts the judge rejected scored
 * 0.92 with the coach; the app was disagreeing with itself.
 *
 * So a text reject is re-judged against the sounds, and only in that
 * direction: the coach can rescue, never fail. The bar is high on purpose —
 * every sound at least CLOSE by the coach's own line and the line as a whole
 * well above it — because the coach is calibrated on synthesised speech and
 * the cost of a wrong rescue is a star for a mispronunciation, which is the
 * complaint this whole loop exists to stop. Measured in docs/loop-accuracy.md.
 */
object CoachRescue {

    /** The line as a whole, on the coach's 0..1 scale (1 is every sound flawless). */
    const val MIN_OVERALL = 0.8f

    /** No single sound below this — the coach's own CLOSE line, under which
     *  it calls a sound WRONG. */
    const val MIN_PHONE = 0.5f

    /** How long a reject waits for a coach that is already running. Past it
     *  the reject stands: a slow model must not hold the room. */
    const val WAIT_MS = 3_000L

    fun accepts(score: PronunciationScore?): Boolean =
        score != null && score.phonemes.isNotEmpty() &&
            score.overall >= MIN_OVERALL && score.phonemes.all { it.score >= MIN_PHONE }
}
