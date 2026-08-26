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
    /** Asset file name under `kokoro/`, and the stored preference value. */
    val id: String,
    /** What a parent sees. Kokoro's own names, which are already human. */
    val label: String,
    val accent: Accent,
    val gender: Gender,
) {
    enum class Accent { AMERICAN, BRITISH }
    enum class Gender { FEMALE, MALE }
}

object TukiVoices {

    /**
     * Default. An adult American female voice — chosen because it was the one
     * evaluated, NOT because it is known best for the audience. For a
     * 4-year-old Hebrew speaker a slower, clearer voice may well score higher
     * than a more natural one; that is what the picker exists to find out.
     */
    const val DEFAULT_ID = "af_heart.bin"

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
    )

    /** Falls back to the default rather than throwing: a preference written by
     *  a newer build (or a hand-edited profile) must not break speech. */
    fun byId(id: String?): TukiVoice =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
