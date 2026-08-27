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
)

/**
 * Which of the four learner tracks this profile follows (docs/learner-tracks.md).
 *
 * The five user types the product serves collapse to four: types 2 and 4
 * (young adults and adults who never learned English) differ in theming and
 * pace, not pedagogy. The track is a config bundle — persona, reply budget,
 * Hebrew scaffolding, feedback style — not a code fork.
 */
@Serializable
enum class LearnerTrack {
    /** Young kids, pre-alphabet. Audio and pictures; Hebrew TEXT is useless here. */
    PRE_READER,

    /** Never learned, or forgot. Bilingual text and audio; Hebrew fades as level rises. */
    BEGINNER,

    /** Bagrut / college prep. Text-first, explicit metalinguistic feedback. */
    EXAM,

    /** Learned it, wants to be better. Conversation-first; Hebrew is an escape hatch. */
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
     * Which track the tutor teaches to. Defaults to BEGINNER — the widest
     * audience and the safest guess for a profile created before tracks
     * existed. A parent changes it in the Parent Zone.
     */
    val track: LearnerTrack = LearnerTrack.BEGINNER,
    /**
     * Stickers the child has picked, oldest first. The reward loop's only
     * durable state; ids are stable strings from the sticker book so a future
     * book can add to it without invalidating a collection.
     */
    val stickers: List<String> = emptyList(),
    val parentSettings: ParentSettings = ParentSettings(),
) {
    companion object {
        val EMPTY = LearnerProfile()
    }
}
