package org.sisam.langtutor.speech

/**
 * The English Kokoro voices bundled in the APK.
 *
 * A Kokoro "voice" is a 510x256 float conditioning table, not a model — every
 * one is exactly 522,240 bytes and the same 86 MB model synthesizes all of
 * them. That is why carrying the whole English set costs ~14 MB and switching
 * voices is instant rather than a download.
 *
 * Pure JVM so the catalogue is unit-testable and the UI has something to list
 * without touching the engine.
 */
data class TukiVoice(
    /** Asset file name under `kokoro/`, and the stored preference value.
     *  A blended voice has no file of its own — see [blend]. */
    val id: String,
    /** What a parent sees. Kokoro's own names, which are already human. */
    val label: String,
    val accent: Accent,
    val gender: Gender,
    /** Set when this voice is MADE from bundled ones rather than shipped. */
    val blend: VoiceBlend? = null,
    /**
     * The style table to read, when it is not this voice's own [id].
     *
     * An accent voice is a shipped table plus a [Phonology]: the table says
     * who is speaking, the phonology says where they are from. It needs an id
     * of its own so the picker and the stored preference can name it, and
     * that id is not an asset.
     */
    val table: String? = null,
    /** How this voice behaves on personality lines; null means the parrot. */
    val character: VoiceCharacter? = null,
    /**
     * The accent this voice speaks in.
     *
     * Not part of the blend, and it cannot be: a style table carries timbre,
     * never phonology (see [Phonology]). A character that is meant to sound
     * from somewhere needs BOTH — the table for who is speaking, this for
     * what sounds come out.
     */
    val phonology: Phonology = Phonology.GENERAL_AMERICAN,
) {
    enum class Accent { AMERICAN, BRITISH, CHARACTER }
    enum class Gender { FEMALE, MALE }

    /** Every table this voice needs present in the build to be usable. */
    val sources: List<String> get() = blend?.let { listOf(it.a, it.b) } ?: listOf(table ?: id)
}

/**
 * A voice made by mixing two conditioning tables.
 *
 * Kokoro's "voice" is a style embedding, and embeddings interpolate: the
 * weighted sum of two tables is a real voice sitting between them, not a
 * crossfade of two recordings. That is the only way to add a timbre this
 * model was not shipped with, short of adding a second TTS — and it costs
 * nothing at all, because both tables are already in the APK.
 */
data class VoiceBlend(
    /** Asset name of the table at [weight]. */
    val a: String,
    /** Asset name of the table at 1 - [weight]. */
    val b: String,
    val weight: Float,
) {
    companion object {
        /**
         * Row-wise linear mix. The tables are the same shape by construction
         * (510x256 floats), and a mismatch means a build carrying tables from
         * two different exports — which would produce noise, so it throws
         * rather than blending whatever overlaps.
         */
        fun mix(a: FloatArray, b: FloatArray, weight: Float): FloatArray {
            require(a.size == b.size) { "voice tables differ: ${a.size} vs ${b.size}" }
            val w = weight.coerceIn(0f, 1f)
            return FloatArray(a.size) { i -> a[i] * w + b[i] * (1f - w) }
        }
    }
}

/**
 * How a voice sounds when it is being a CHARACTER rather than a teacher.
 *
 * Only personality lines pass through this — praise, encouragement, the lines
 * whose exact phonetics nobody is learning from. A teaching line the child is
 * meant to copy never gets a treatment; see ParrotEffect for the doctrine.
 */
data class VoiceCharacter(
    /** Resample factor: above 1 raises pitch and formants, below 1 lowers. */
    val pitch: Float,
    /** Multiplies the requested speed — a character may talk slower. */
    val rate: Float = 1f,
    val warbleHz: Float,
    val warbleDepth: Float,
    /** The little trill announcing the character before the words. */
    val flourish: Boolean = true,
)

object TukiVoices {

    /**
     * Default. An adult American female voice — chosen because it was the one
     * evaluated, NOT because it is known best for the audience. For a
     * 4-year-old Hebrew speaker a slower, clearer voice may well score higher
     * than a more natural one; that is what the picker exists to find out.
     */
    const val DEFAULT_ID = "af_heart.bin"

    /**
     * The sea captain: an old, gruff, unhurried voice for a child who wants
     * Tuki to be somebody.
     *
     * Kokoro ships no Scottish voice, and no mix of the ones it does ship
     * will invent one — this is the nearest the bundled set reaches: the
     * gruffest British male weighted against the oldest-sounding one, then
     * slowed and dropped in pitch for personality lines. The weights are a
     * starting point chosen by construction, not by ear; they are meant to be
     * tuned on a device with the picker's own preview button.
     */
    const val CAPTAIN_ID = "captain.blend"

    private fun v(name: String, label: String) = TukiVoice(
        id = "$name.bin",
        label = label,
        accent = if (name.startsWith("b")) TukiVoice.Accent.BRITISH else TukiVoice.Accent.AMERICAN,
        gender = if (name[1] == 'f') TukiVoice.Gender.FEMALE else TukiVoice.Gender.MALE,
    )

    val ALL: List<TukiVoice> = listOf(
        v("af_heart", "Heart"), v("af_alloy", "Alloy"), v("af_aoede", "Aoede"),
        v("af_bella", "Bella"), v("af_jessica", "Jessica"), v("af_kore", "Kore"),
        v("af_nicole", "Nicole"), v("af_nova", "Nova"), v("af_river", "River"),
        v("af_sarah", "Sarah"), v("af_sky", "Sky"),
        v("am_adam", "Adam"), v("am_echo", "Echo"), v("am_eric", "Eric"),
        v("am_fenrir", "Fenrir"), v("am_liam", "Liam"), v("am_michael", "Michael"),
        v("am_onyx", "Onyx"), v("am_puck", "Puck"), v("am_santa", "Santa"),
        v("bf_alice", "Alice"), v("bf_emma", "Emma"), v("bf_isabella", "Isabella"),
        v("bf_lily", "Lily"),
        v("bm_daniel", "Daniel"), v("bm_fable", "Fable"), v("bm_george", "George"),
        v("bm_lewis", "Lewis"),
        TukiVoice(
            id = CAPTAIN_ID,
            label = "Captain",
            accent = TukiVoice.Accent.CHARACTER,
            gender = TukiVoice.Gender.MALE,
            blend = VoiceBlend(a = "bm_lewis.bin", b = "bm_george.bin", weight = 0.65f),
            // The blend alone would be an old Englishman: the burr is in the
            // phonemes, and only this line puts it there.
            character = VoiceCharacter(
                // Down about a tone and a half, and a shade slower: old and
                // unhurried rather than the parrot's small-and-quick.
                pitch = 0.90f,
                rate = 0.94f,
                // A slow, shallow waver — an old voice, not a bird.
                warbleHz = 3.0f,
                warbleDepth = 0.05f,
                flourish = false,
            ),
            // The blend alone would be an old Englishman: the burr is in the
            // phonemes, and only this line puts it there.
            phonology = Phonology.SCOTTISH,
        ),
        accent("irish", "Irish", "bf_emma", Phonology.IRISH),
        accent("italian", "Italian", "am_onyx", Phonology.ITALIAN),
        accent("french", "French", "af_nicole", Phonology.FRENCH),
        accent("spanish", "Spanish", "am_liam", Phonology.SPANISH),
        accent("hebrew", "Hebrew", "af_sarah", Phonology.HEBREW),
        accent("arabic", "Arabic", "am_eric", Phonology.ARABIC),
        accent("mandarin", "Mandarin", "bf_lily", Phonology.MANDARIN),
    )

    /**
     * English spoken with an accent.
     *
     * Each is an ordinary shipped table plus a [Phonology] — the table is who
     * is speaking, the phonology is where they are from, and neither can do
     * the other's job. Different tables on purpose, so two accents are never
     * the same person saying different sounds.
     *
     * Labelled by LANGUAGE, which is the accurate name for what a rewrite of
     * English phonemes by first-language transfer actually is, and the only
     * framing that stays a description rather than an impression of a group.
     */
    private fun accent(key: String, label: String, table: String, phonology: Phonology) = TukiVoice(
        id = "$key.accent",
        label = label,
        accent = TukiVoice.Accent.CHARACTER,
        gender = if (table[1] == 'f') TukiVoice.Gender.FEMALE else TukiVoice.Gender.MALE,
        table = "$table.bin",
        phonology = phonology,
    )

    /** Falls back to the default rather than throwing: a preference written by
     *  a newer build (or a hand-edited profile) must not break speech. */
    fun byId(id: String?): TukiVoice =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
