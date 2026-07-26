package org.sisam.langtutor.speech

/**
 * Rebuilds pointed Hebrew from the phonikud nikud-model's per-character
 * predictions — the pure-logic half of the on-device diacritizer (the ONNX
 * session lives in the app engine; this stays JVM-testable). Port of the
 * reconstruction loop in phonikud-onnx's model.py: per Hebrew letter append
 * shin/sin dot, the argmax'd niqqud class, then stress (ole), vocal-shva
 * (meteg) and prefix ('|') marks when their binary heads fire.
 *
 * The character order per letter matches the reference exactly — the
 * downstream [PhonikudPhonemizer] normalization re-sorts marks anyway, but
 * golden parity is byte-level so we don't rely on that.
 */
object NikudRestorer {

    /** Strip niqqud + phonikud marks; the model expects bare text. */
    fun removeNikud(text: String): String = PhonikudLexicon.NIKUD_STRIP.replace(text, "")

    /**
     * @param text UNPOINTED text, one prediction per char (see [removeNikud])
     * @param nikudClass argmax index into [PhonikudLexicon.NIKUD_CLASSES] per char
     * @param shinClass argmax index into [PhonikudLexicon.SHIN_CLASSES] per char
     * @param stress binary head: char carries the stress mark
     * @param vocalShva binary head: char carries the vocal-shva mark
     * @param prefix binary head: char ends a prefix particle
     */
    fun restore(
        text: String,
        nikudClass: IntArray,
        shinClass: IntArray,
        stress: BooleanArray,
        vocalShva: BooleanArray,
        prefix: BooleanArray,
    ): String = buildString {
        val l = PhonikudLexicon
        for (i in text.indices) {
            val ch = text[i]
            if (!l.isHebrewLetter(ch)) {
                append(ch)
                continue
            }
            var nikud = l.NIKUD_CLASSES.getOrElse(nikudClass[i]) { "" }
            val shin = if (ch == 'ש') l.SHIN_CLASSES[shinClass[i]] else ""
            if (nikud == l.MAT_LECT) {
                if (ch !in l.MATRES_LETTERS) {
                    nikud = ""
                } else {
                    // Matres lectionis: the letter itself is the vowel — keep it
                    // bare (reference drops even stress marks here).
                    append(ch)
                    continue
                }
            }
            append(ch)
            append(shin)
            append(nikud)
            if (stress[i]) append(l.HATAMA)
            if (vocalShva[i]) append(l.VOCAL_SHVA)
            if (prefix[i]) append(l.PREFIX)
        }
    }
}
