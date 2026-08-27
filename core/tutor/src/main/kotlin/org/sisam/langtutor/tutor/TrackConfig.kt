package org.sisam.langtutor.tutor

import org.sisam.langtutor.profile.LearnerTrack

/**
 * What a [LearnerTrack] actually changes about a turn.
 *
 * The levers were always in the code — they were just hardcoded for one
 * audience (docs/learner-tracks.md). This gathers them into one bundle per
 * track so serving an adult beginner is a config lookup, not a code fork.
 *
 * The split that matters pedagogically is [explicitCorrection]: the SLA
 * literature divides exactly here. Naming the rule measurably helps adults and
 * mostly bruises a young child's willingness to speak, so PRE_READER gets
 * recasts only while EXAM gets the rule named.
 */
data class TrackConfig(
    /** Extra system-prompt lines appended to the shared tutor persona. */
    val personaSuffix: String,
    /** Reply budget in tokens. Turn time scales almost linearly with this. */
    val replyTokens: Int,
    /**
     * May this track be OFFERED written Hebrew explanations? False for
     * pre-readers — a child who cannot read Hebrew either gains nothing from
     * Hebrew text, and offering it is a button that does nothing for them.
     * Their path is pre-recorded spoken Hebrew (docs/product-phases.md).
     */
    val hebrewTextUseful: Boolean,
) {
    companion object {

        fun of(track: LearnerTrack): TrackConfig = when (track) {
            LearnerTrack.PRE_READER -> TrackConfig(
                personaSuffix = "The learner is a young child who cannot read yet. " +
                    "Use only words a five-year-old knows. One short sentence, then one " +
                    "short question. Never name a grammar rule; if they make a mistake, " +
                    "simply say the sentence back correctly and warmly.",
                replyTokens = 48,
                hebrewTextUseful = false,
            )

            LearnerTrack.BEGINNER -> TrackConfig(
                personaSuffix = "The learner is a teenager or adult starting English from " +
                    "the beginning. Be encouraging and never childish. Keep sentences short " +
                    "and concrete. After a mistake, say the correct sentence and add at most " +
                    "one plain-language line about why.",
                replyTokens = 96,
                hebrewTextUseful = true,
            )

            LearnerTrack.EXAM -> TrackConfig(
                personaSuffix = "The learner is preparing for an English exam. Be precise " +
                    "and businesslike, not playful. Name the grammar rule or pattern behind " +
                    "any correction, and give one short example of the same pattern.",
                replyTokens = 128,
                hebrewTextUseful = true,
            )

            LearnerTrack.IMPROVER -> TrackConfig(
                personaSuffix = "The learner already speaks English and wants to sound " +
                    "better. Talk to them as an equal. Keep the conversation going first; " +
                    "note register, idiom or naturalness only when it genuinely matters.",
                replyTokens = 112,
                hebrewTextUseful = true,
            )
        }
    }
}
