package org.sisam.langtutor.tutor

/**
 * What a learner's Level (1–7) actually changes about a turn.
 *
 * Levels replaced the four age-flavored "tracks" (docs/learner-levels.md):
 * the app serves non-native speakers of ALL ages, so nothing here may assume
 * an age — a Level 1 learner is anyone with only a few English words, six or
 * sixty. Every dial the old tracks moved lives here as a function of
 * proficiency instead: the tutor's register, the reply budget, how a mistake
 * is answered, and how much Hebrew scaffolding is offered.
 *
 * The pedagogy carried over intact. The correction split still follows the
 * SLA literature — recasts only at the bottom (naming rules bruises a
 * beginner's willingness to speak), explicit metalinguistic feedback at the
 * top. Hebrew scaffolding fades with proficiency, ending entirely at Levels
 * 6–7, where immersion IS the product.
 */
data class LevelConfig(
    /** 1–7, [of] clamps. */
    val level: Int,
    /** Extra system-prompt lines appended to the shared tutor persona. */
    val personaSuffix: String,
    /** Reply budget in tokens. Turn time scales almost linearly with this. */
    val replyTokens: Int,
    /**
     * May this learner be OFFERED written Hebrew explanations? On through
     * Level 5; off at 6–7, where Hebrew would be a crutch against the
     * immersion the level exists to provide. (This deliberately REVERSED the
     * old pre-reader veto: "cannot read Hebrew" was an age assumption — a
     * Level 1 ADULT reads Hebrew fine, and a Level 1 child who cannot is
     * served by the spoken-Hebrew path, which has its own gate.)
     */
    val hebrewTextUseful: Boolean,
    /**
     * Default for the Hebrew-letter pronunciation gloss under English text.
     * On through Level 3 — a target you cannot decode is a target you can
     * only guess at from audio. Off above: it competes with the spelling the
     * learner is internalising. Always overridable in the Parent Zone.
     */
    val transliterationByDefault: Boolean,
    /**
     * Default for the Hebrew MEANING row. On through Level 4; above that the
     * learner should be deriving meaning from the English first. Separately
     * overridable, because how-to-say-it and what-it-means are different
     * scaffolds.
     */
    val translationByDefault: Boolean,
    /**
     * Whether earned stickers interrupt with the full ceremony room. Level 1
     * only — the celebration IS the reward loop at the very start; above
     * that the sticker lands quietly in the book. (Exact parity with the old
     * behaviour, where only the pre-reader profile and 4-6 units redirected.)
     */
    val stickerCeremony: Boolean,
) {
    companion object {

        const val MIN = 1
        const val MAX = 7

        /** Units at or below this level keep the short-reply floor and the
         *  ceremony redirect — content shaped for the very start. Level 1
         *  exactly, matching the old 4-6 band; raising it would cap every
         *  current unit's replies at the floor. */
        const val EARLY_UNIT_LEVEL = 1

        private val PERSONAS = listOf(
            // 1 — first words
            "The learner knows only a few English words and may not read " +
                "English yet. Use only the most common words. One very short " +
                "sentence, then one very short question. Never name a grammar " +
                "rule; after a mistake, warmly say the sentence back correctly.",
            // 2 — first sentences
            "The learner is just starting: common words and very simple " +
                "sentences. Keep sentences short and concrete. After a mistake, " +
                "say the correct sentence warmly; explain grammar only if asked.",
            // 3 — simple everyday talk
            "The learner handles simple everyday sentences, including the " +
                "past tense. Keep language concrete. After a mistake, say the " +
                "correct sentence and add at most one plain-language line about why.",
            // 4 — everyday English
            "The learner manages everyday English and is building confidence. " +
                "Vary your sentence shapes. After a mistake, give the correction " +
                "and one short, plain explanation.",
            // 5 — conversations
            "The learner converses fairly freely and is expanding their range. " +
                "Talk naturally. Name the grammar pattern behind a correction, " +
                "with one short example of the same pattern.",
            // 6 — rich English
            "The learner is advanced. Talk to them as an equal, with idiomatic " +
                "English. Correct only what genuinely matters — register, idiom, " +
                "precision — and name the pattern when you do.",
            // 7 — mastery
            "The learner is near mastery and wants polish. Converse as with a " +
                "fluent peer; offer nuance, idiom and register, and correct with " +
                "precision and respect.",
        )

        private val TOKENS = listOf(48, 64, 80, 96, 112, 128, 128)

        fun of(level: Int): LevelConfig {
            val at = level.coerceIn(MIN, MAX)
            return LevelConfig(
                level = at,
                personaSuffix = PERSONAS[at - 1],
                replyTokens = TOKENS[at - 1],
                hebrewTextUseful = at <= 5,
                transliterationByDefault = at <= 3,
                translationByDefault = at <= 4,
                stickerCeremony = at <= 1,
            )
        }
    }
}
