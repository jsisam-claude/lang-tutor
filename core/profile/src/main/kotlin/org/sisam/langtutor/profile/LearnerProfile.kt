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
    /**
     * Show the Hebrew-letter pronunciation under English lines.
     *
     * Null means "whatever this learner's track wants" ([TrackConfig]), which
     * is the right default for everyone who never opens this screen: a
     * pre-reader and an exam candidate need opposite things and neither should
     * have to ask. Setting it true or false pins the choice — an adult who
     * finds the gloss patronising, or a child whose track says no but who is
     * still decoding, both get to override.
     */
    val showTransliteration: Boolean? = null,
    /**
     * Show the Hebrew MEANING under English lines — a separate question from
     * [showTransliteration], which shows how to say it.
     *
     * Null follows the track's [TrackConfig.hebrewTextUseful], which already
     * asks the right question: is written Hebrew any use to this learner? A
     * pre-reader who cannot read Hebrew gains nothing from a translation
     * while still gaining from a pronunciation key, which is exactly why
     * these are two settings and not one.
     */
    val showTranslation: Boolean? = null,
    /**
     * Try the Edge TPU before the GPU when loading the model. Experimental,
     * off by default, and deliberately not something a learner ever sees.
     *
     * It is a probe: on Tensor G4 it may hang the load, crash the process, or
     * leave the GPU unusable so the session runs on CPU at a fifth the speed.
     * Worth having a switch for — the device's own library list says the Edge
     * TPU is at least visible to apps — but not worth defaulting on.
     */
    val tryNpuBackend: Boolean = false,
    /**
     * Prefer a 60 Hz display refresh while the model is decoding.
     * Experimental A/B: UI composition shares the GPU with decode, and half
     * the frames may mean measurably faster tokens — or nothing. Harmless
     * either way (the panel just runs at 60 for those seconds), but it stays
     * a switch until a device says which.
     */
    val capRefreshDuringDecode: Boolean = false,
)

/**
 * LEGACY: the four learner tracks that predate Levels 1–7.
 *
 * Kept only so existing stored profiles keep deserializing and can be
 * migrated ([LearnerProfile.effectiveLevel] maps a track to its nearest
 * level). Nothing should branch on this anymore — the app's audience is
 * non-native speakers at proficiency Levels 1–7, not age groups, and every
 * dial the track used to move now lives in `LevelConfig`.
 */
@Serializable
enum class LearnerTrack {
    PRE_READER,
    BEGINNER,
    EXAM,
    IMPROVER,
}

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
    /**
     * LEGACY — see [LearnerTrack]. Read only by [effectiveLevel] to migrate
     * a stored profile that predates levels; never written anymore.
     */
    val track: LearnerTrack = LearnerTrack.BEGINNER,
    /**
     * The learner's proficiency, 1–7 (docs/learner-levels.md). 0 means
     * "never chosen": [effectiveLevel] then derives a starting level from the
     * legacy track, so an existing profile lands where its track pointed and
     * a fresh one starts at the widest default. Levels replace every age
     * construct — the app serves non-native speakers of ALL ages.
     */
    val learnerLevel: Int = 0,
    /**
     * Stickers the child has picked, oldest first. The reward loop's only
     * durable state; ids are stable strings from the sticker book so a future
     * book can add to it without invalidating a collection.
     */
    val stickers: List<String> = emptyList(),
    val parentSettings: ParentSettings = ParentSettings(),
) {
    /**
     * The level every gate and config reads: the chosen [learnerLevel], or —
     * for a profile that predates levels — the legacy track's nearest level.
     */
    val effectiveLevel: Int
        get() = if (learnerLevel in 1..7) learnerLevel else when (track) {
            LearnerTrack.PRE_READER -> 1
            LearnerTrack.BEGINNER -> 2
            LearnerTrack.EXAM -> 4
            LearnerTrack.IMPROVER -> 5
        }

    companion object {
        val EMPTY = LearnerProfile()
    }
}
