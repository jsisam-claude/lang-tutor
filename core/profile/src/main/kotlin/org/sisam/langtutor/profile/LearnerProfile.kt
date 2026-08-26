package org.sisam.langtutor.profile

import kotlinx.serialization.Serializable

@Serializable
data class ParentSettings(
    val dailyMinutesLimit: Int = 20,
    /** BCP-47 tag for the UI language ("he" default market, "en" available). */
    val uiLanguage: String = "he",
    /**
     * Asset file name of Tuki's voice under `kokoro/`. Null means "whatever the
     * build defaults to", so an existing profile keeps working and a future
     * default change reaches people who never chose explicitly.
     */
    val voiceId: String? = null,
)

@Serializable
data class UnitProgress(
    val completedActivities: Int = 0,
    val stars: Int = 0,
)

/**
 * The child's local learning state. Device-local only — never transmitted;
 * deleting it from the Parent Zone is a real, complete deletion.
 */
@Serializable
data class LearnerProfile(
    val childName: String = "",
    val ageYears: Int? = null,
    val level: String = "L0",
    val xp: Int = 0,
    val unitProgress: Map<String, UnitProgress> = emptyMap(),
    /** BKT mastery estimates per skill id — the input to adaptivity and the parent skill map. */
    val skills: Map<String, SkillState> = emptyMap(),
    val parentSettings: ParentSettings = ParentSettings(),
) {
    companion object {
        val EMPTY = LearnerProfile()
    }
}
