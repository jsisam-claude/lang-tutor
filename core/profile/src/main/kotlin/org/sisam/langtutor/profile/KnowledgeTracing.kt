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
