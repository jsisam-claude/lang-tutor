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
 * produced.
 *
 * So a text reject is re-judged against the sounds, and only in that
 * direction: the coach can rescue, never fail. Two limits, both measured
 * (docs/loop-accuracy.md §5):
 *
 *  - Only a reject the coach can speak to. It aligns sounds in the target's
 *    order and cannot see that "you are" was said for *Are you*, or that
 *    "can" was said for *can't* — it scores the *t* it was told to expect as
 *    merely CLOSE. A moved word or a negation, dropped or added, stays a
 *    reject whatever the coach says; the rescue is for a word left out or
 *    misheard.
 *  - Every sound GOOD, by the coach's own line. Any looser and it rescues
 *    minimal-pair mispronunciations it scored CLOSE — "dead is ready",
 *    "an eppel" — which is the complaint this whole loop exists to stop. At
 *    GOOD: 10 of 75 correct rejects rescued, 2 of 145 wrong ones (both a
 *    vowel the coach cannot separate). Targeting only the missed word's
 *    sounds was measured too, and was worse.
 *
 * The bar is high because the coach is calibrated on synthesised speech;
 * on a real voice it rescues less, which is the safe direction.
 */
object CoachRescue {

    /** The line as a whole, on the coach's 0..1 scale (1 is every sound flawless). */
    const val MIN_OVERALL = 0.8f

    /** Every sound at least this: the line the feedback row draws between
     *  green and amber, i.e. the coach's GOOD verdict and nothing below it. */
    const val MIN_PHONE = SoundSkills.GOOD

    /** How long a reject waits for a coach that is already running. Past it
     *  the reject stands: a slow model must not hold the room. */
    const val WAIT_MS = 3_000L

    fun accepts(score: PronunciationScore?, judgement: WordMatch.Judgement): Boolean =
        score != null && score.phonemes.isNotEmpty() &&
            judgement.moved == 0 && judgement.negationDropped == 0 && judgement.negationAdded == 0 &&
            score.overall >= MIN_OVERALL && score.phonemes.all { it.score >= MIN_PHONE }
}
