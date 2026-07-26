package org.sisam.langtutor.speech

/**
 * Tables for the Hebrew rule phonemizer — a faithful Kotlin port of the
 * phonikud project's lexicon (github.com/thewh1teagle/phonikud, MIT). The
 * character↔phoneme correspondences are standard Modern-Hebrew phonology;
 * golden tests pin this port to the Python reference output byte-for-byte.
 * Niqqud/marks are written as \u escapes — combining marks pasted as glyphs
 * are unreviewable and silently reorder in editors.
 */
internal object PhonikudLexicon {

    // Non-standard diacritics the nikud model emits beyond Unicode niqqud.
    const val VOCAL_SHVA = 'ֽ' // meteg — marks mobile shva
    const val HATAMA = '֫' // ole — marks stress
    const val PREFIX = '|'
    const val NIKUD_HASER = '֯' // masora — letter is silent
    const val STRESS_PHONEME = 'ˈ' // ˈ

    const val DAGESH = 'ּ'
    const val SHVA = 'ְ'
    const val HOLAM = 'ֹ'
    const val HIRIK = 'ִ'
    const val KAMATZ = 'ָ'
    const val PATAH = 'ַ'
    const val TSERE = 'ֵ'
    const val SEGOL = 'ֶ'
    const val KUBUTS = 'ֻ'
    const val SIN_DOT = 'ׂ'
    const val HATAF_KAMATZ = 'ֳ'
    const val KAMATZ_KATAN = 'ׇ'

    /** Contiguous Hebrew run (letters+marks, ole, meteg, masora, bar, geresh, quote). */
    val HE_PATTERN = Regex("[ְ-תֽ֫|֯'\"]+")

    /** What removeNikud strips: the standard mark range + our prefix bar. */
    val NIKUD_STRIP = Regex("[֐-ׇ|]")

    val PATAH_LIKE = Regex("[ַ-ָ]")

    val PUNCTUATION = setOf('.', ',', '!', '?', ' ')

    /** Geresh consonants (loanword sounds): gimel, zayin, tav, tsadi, final tsadi. */
    val GERESH_PHONEMES = mapOf(
        "ג" to "dʒ", // ג' -> dʒ
        "ז" to "ʒ", // ז' -> ʒ
        "ת" to "ta", // ת'
        "צ" to "tʃ", // צ' -> tʃ
        "ץ" to "tʃ", // ץ' -> tʃ
    )

    val LETTER_PHONEMES = mapOf(
        "א" to "ʔ", // א -> ʔ
        "ב" to "v", // ב
        "ג" to "g", // ג
        "ד" to "d", // ד
        "ה" to "h", // ה
        "ו" to "v", // ו
        "ז" to "z", // ז
        "ח" to "x", // ח
        "ט" to "t", // ט
        "י" to "j", // י
        "ך" to "x", // ך
        "כ" to "x", // כ
        "ל" to "l", // ל
        "ם" to "m", // ם
        "מ" to "m", // מ
        "ן" to "n", // ן
        "נ" to "n", // נ
        "ס" to "s", // ס
        "ע" to "ʔ", // ע -> ʔ
        "פ" to "f", // פ
        "ף" to "f", // ף
        "ץ" to "ts", // ץ
        "צ" to "ts", // צ
        "ק" to "k", // ק
        "ר" to "r", // ר
        "ש" to "ʃ", // ש -> ʃ
        "ת" to "t", // ת
        // Beged-kefet with dagesh
        "בּ" to "b", // בּ
        "כּ" to "k", // כּ
        "פּ" to "p", // פּ
        // Shin/Sin dots
        "שׁ" to "ʃ", // שׁ -> ʃ
        "שׂ" to "s", // שׂ
        "'" to "",
    )

    val NIKUD_PHONEMES = mapOf(
        'ִ' to "i", // hiriq
        'ֱ' to "e", // hataf segol
        'ֵ' to "e", // tsere
        'ֶ' to "e", // segol
        'ֲ' to "a", // hataf patah
        'ַ' to "a", // patah
        'ׇ' to "o", // kamatz katan
        'ֹ' to "o", // holam
        'ֺ' to "o", // holam haser for vav
        'ֻ' to "u", // qubuts
        'ֳ' to "o", // hataf kamatz
        'ָ' to "a", // kamatz
        HATAMA to STRESS_PHONEME.toString(),
        VOCAL_SHVA to "e",
    )

    /** Final-patah gutturals (patah gnuva): het, he, ayin. */
    val PATAH_GNUVA = mapOf("ח" to "ax", "ה" to "ah", "ע" to "a")

    /** Modern pronunciation respelling applied per word, last. */
    val MODERN_SCHEMA = listOf("x" to "χ", "r" to "ʁ", "g" to "ɡ")

    /** Every phoneme char the pipeline may legitimately emit. */
    val PHONEME_CHARS: Set<Char> = buildSet {
        (NIKUD_PHONEMES.values + LETTER_PHONEMES.values + GERESH_PHONEMES.values)
            .forEach { s -> s.forEach { add(it) } }
        MODERN_SCHEMA.forEach { (_, v) -> v.forEach { add(it) } }
        add('w') // SPECIAL_PHONEMES
    }

    /** Vowel-bearing marks for syllable detection (U+05B1..U+05BB, kamatz katan, meteg). */
    val VOWEL_DIACS: Set<Char> = buildSet {
        for (c in 'ֱ'..'ֻ') add(c)
        add('ׇ')
        add(VOCAL_SHVA)
    }

    const val MAT_LECT = "<MAT_LECT>"

    /**
     * Output classes of the nikud model's main head (index = argmax). Order is
     * the model contract: none, matres marker, dagesh, the 12 plain marks
     * U+05B0..U+05BB, the same 12 preceded by dagesh, then kamatz katan ±dagesh.
     */
    val NIKUD_CLASSES: List<String> = buildList {
        add("")
        add(MAT_LECT)
        add(DAGESH.toString())
        for (c in 'ְ'..'ֻ') add(c.toString())
        for (c in 'ְ'..'ֻ') add("$DAGESH$c")
        add(KAMATZ_KATAN.toString())
        add("$DAGESH$KAMATZ_KATAN")
    }
    val SHIN_CLASSES = listOf("ׁ", "ׂ")
    val MATRES_LETTERS = setOf('א', 'ו', 'י') // א ו י

    fun isHebrewLetter(c: Char): Boolean = c in 'א'..'ת'
}
