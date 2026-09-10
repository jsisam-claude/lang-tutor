package org.sisam.langtutor.profile

import kotlinx.serialization.Serializable

/** Per-skill mastery estimate (skill ids like "vocab:ball", "phoneme:th"). */
@Serializable
data class SkillState(
    val pKnown: Double = 0.1,
    val attempts: Int = 0,
)

/**
 * Classic Bayesian Knowledge Tracing. Evidence pass finding #6: at our data
 * scale, tiny classical KT matches deep models (Gervet 2020) and runs in
 * microseconds on-device — and the LLM is NEVER the learner model. Drives
 * activity selection, correction-readiness gating, and the parent skill map.
 */
class BktModel(
    private val pLearn: Double = 0.15,
    private val pSlip: Double = 0.10,
    private val pGuess: Double = 0.20,
) {

    fun update(state: SkillState, correct: Boolean): SkillState {
        val p = state.pKnown
        val posterior = if (correct) {
            p * (1 - pSlip) / (p * (1 - pSlip) + (1 - p) * pGuess)
        } else {
            p * pSlip / (p * pSlip + (1 - p) * (1 - pGuess))
        }
        val next = posterior + (1 - posterior) * pLearn
        return SkillState(pKnown = next.coerceIn(0.0, 1.0), attempts = state.attempts + 1)
    }

    fun isMastered(state: SkillState, threshold: Double = MASTERY_THRESHOLD): Boolean =
        state.pKnown >= threshold

    companion object {
        const val MASTERY_THRESHOLD = 0.95
    }
}

/**
 * What a skill id looks like, so every room spells one the same way.
 *
 * The grain is chosen to be BOUNDED by the content rather than by how much
 * a learner practises: 37 themes, 333 sentence frames, 15 target sounds and
 * the few hundred words that have a picture. A profile therefore holds at
 * most about seven hundred entries however long the app is used, and needs
 * no eviction rule to keep it honest.
 *
 * The frame is the interesting one. `PhraseSentence.frame` has carried 333
 * distinct grammar patterns since the bank was written and no code has ever
 * read it; as a skill id it is exactly the right grain — "going-to" is a
 * thing a learner can be good at, while a single sentence is not.
 */
object Skill {
    /** A word with a picture: the picture room's cards and a pack gap. */
    fun word(en: String) = "word:" + en.lowercase()

    /** A phoneme the twisters drill, by their own sound key. */
    fun sound(key: String) = "sound:$key"

    /** A phrasebank topic. */
    fun theme(id: String) = "theme:$id"

    /** A grammar pattern — the phrasebank's own frame tag. */
    fun frame(id: String) = "frame:$id"

    /**
     * An English tense, by the phrasebank's own tag: "tense:past-perfect".
     *
     * Bounded like every other id here — the bank uses 17 tags and a line
     * carries exactly one — so a profile that practises for years still holds
     * seventeen of these and not one more.
     */
    fun tense(id: String) = "tense:$id"

    /** The namespace of an id, for grouping in the Parent Zone. */
    fun kindOf(id: String): String = id.substringBefore(':', "")

    /** The part a person reads: "going-to", "bee", "th". */
    fun labelOf(id: String): String = id.substringAfter(':')
}

/**
 * The one place an answer becomes evidence.
 *
 * Every room that grades something calls [observe] with the skills that
 * answer exercised, and nothing else in the app writes [LearnerProfile.skills].
 * Keeping it to one function is what stops four rooms from disagreeing about
 * what "correct" means or about which BKT parameters apply.
 *
 * A first-try answer is the only evidence of knowing: a word found on the
 * third tap, or after the room read the line out, is evidence of the
 * opposite, which is why the rooms pass their own notion of "clean".
 */
class SkillTracker(private val model: BktModel = BktModel()) {

    /** Fold one graded answer into the profile. No-op for an empty list, so
     *  a room with nothing to attribute costs nothing. */
    fun observe(profile: LearnerProfile, skills: Collection<String>, correct: Boolean): LearnerProfile {
        if (skills.isEmpty()) return profile
        val updated = profile.skills.toMutableMap()
        for (id in skills.distinct()) {
            updated[id] = model.update(updated[id] ?: SkillState(), correct)
        }
        return profile.copy(skills = updated)
    }

    /**
     * How well a skill is known, 0..1. An id never seen returns [UNSEEN] —
     * deliberately BELOW the prior a first attempt would leave it at, so
     * that a room drawing weakest-first offers new material before it
     * revisits anything, which is what a learner opening a fresh topic
     * expects.
     */
    fun mastery(profile: LearnerProfile, id: String): Double =
        profile.skills[id]?.pKnown ?: UNSEEN

    /** The weakest skills of one kind, worst first: what the Parent Zone
     *  shows and what a future review round would draw from. */
    fun weakest(profile: LearnerProfile, kind: String, limit: Int = 5): List<Pair<String, SkillState>> =
        profile.skills.entries
            .filter { Skill.kindOf(it.key) == kind && it.value.attempts >= MIN_ATTEMPTS }
            .sortedBy { it.value.pKnown }
            .take(limit)
            .map { it.key to it.value }

    companion object {
        /** Below any real estimate, so unseen material is drawn first. */
        const val UNSEEN = 0.0

        /** One answer is noise; a skill is only reported after a few. */
        const val MIN_ATTEMPTS = 3
    }
}
