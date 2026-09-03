package org.sisam.langtutor.speech

/**
 * Which accent a voice speaks in.
 *
 * The thing to understand before touching this file: **a Kokoro voice table
 * does not carry an accent.** The 510x256 style vector controls timbre and
 * delivery — who is speaking, how gruff, how fast — while the phoneme string
 * decides which sounds come out, and that string is ours, produced by
 * [KokoroPhonemizer] from CMUdict. Blending two English voices gives a third
 * English voice. An accent has to be written into the phonemes.
 *
 * So this is where an accent lives, and it is a rewrite of the IPA the front
 * end already produced. Every symbol used here is in the shipped 114-token
 * Kokoro vocabulary AND in the 392-phone vocabulary the pronunciation coach
 * scores against, which is what lets the same string drive synthesis, scoring
 * and the Hebrew gloss without any of the three disagreeing.
 */
enum class Phonology {

    /** What CMUdict gives us, and what every voice but a character speaks. */
    GENERAL_AMERICAN {
        override fun applyTo(ipa: String) = ipa
    },

    /**
     * Scottish Standard English, as far as a phoneme rewrite reaches.
     *
     * Four substitutions, each a real and audible SSE feature:
     *
     *  - FACE and GOAT are monophthongs (`/e/`, `/o/`), not the diphthongs
     *    `/eɪ/` and `/oʊ/` — misaki writes those as the single letters A and O.
     *  - `/ɹ/` becomes the tap `/ɾ/`. English is rhotic here already (CMUdict
     *    is General American), so this changes the r's QUALITY, not whether
     *    it is pronounced, and the tap is the cue an ear catches first.
     *  - The cot–caught merger: `/ɔ/` joins `/ɒ/`.
     *
     * What this is NOT: a native accent. The Scottish vowel length rule and
     * the intonation are not phoneme substitutions and are not here, and the
     * voice underneath is still an English speaker. It reads as someone doing
     * a Scottish accent — which for a character is the point, and for
     * anything claiming authenticity is not enough. A real burr needs a model
     * trained on Scottish speech; see docs/character-voices.md.
     *
     * Every substitution is one symbol for one symbol, so the token count
     * never moves: the style row (indexed by token count), the karaoke word
     * timings and the coach's phone alignment all stay exactly where they
     * were.
     */
    SCOTTISH {
        override fun applyTo(ipa: String): String {
            val out = StringBuilder(ipa.length)
            for (c in ipa) out.append(SUBSTITUTIONS[c] ?: c)
            return out.toString()
        }
    };

    /** [ipa] as this accent would say it. */
    abstract fun applyTo(ipa: String): String

    private companion object {
        val SUBSTITUTIONS = mapOf(
            'A' to 'e',   // FACE:  /eɪ/ -> /e/
            'O' to 'o',   // GOAT:  /oʊ/ -> /o/
            'ɹ' to 'ɾ',   // the tapped r
            'ɔ' to 'ɒ',   // cot-caught merged
        )
    }
}
