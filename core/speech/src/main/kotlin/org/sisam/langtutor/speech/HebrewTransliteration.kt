package org.sisam.langtutor.speech

/**
 * English **sounds** written in Hebrew letters, so a child who reads Hebrew but
 * not yet Latin script can pronounce a line before they can decode it.
 *
 * This is transliteration, not translation: `cat` becomes `קֶט` (how to say it),
 * never `חתול` (what it means). The two serve different learners and the plan
 * (docs/bilingual-gloss.md) keeps room for both; this is the one that unlocks
 * "Repeat after me", because a target you cannot read is a target you cannot
 * attempt.
 *
 * **It is derived, not authored.** The input is the misaki IPA that
 * [KokoroPhonemizer] already computes for every line the app speaks — CMUdict
 * for real words, [RuleG2p] for the rest. So the gloss costs no content work,
 * no second model call, and it exists for LLM-written drill lines and free
 * conversation exactly as it does for the curriculum. It also cannot drift from
 * what Tuki says: the letters and the voice read the same phoneme string.
 *
 * ## The spelling conventions, and why these ones
 *
 * Israeli practice for foreign words is used wherever it exists, and the
 * ambiguous cases are resolved in favour of *teaching* rather than of custom:
 *
 * - **`b` vs `v`** — `בּ` (with dagesh) and `ב`. Custom often writes /v/ as `ו`,
 *   but `ו` is also how /w/ is written, and "very/wery" is a mistake a Hebrew
 *   speaker actually makes. Splitting them across two letters is the whole
 *   point of a pronunciation aid.
 * - **`θ` and `ð`** (`think`, `the`) — `ת׳` and `ד׳`, the geresh convention
 *   Hebrew already uses for Arabic ث/ذ. Nothing in Hebrew makes these sounds,
 *   so a marked letter is more honest than a wrong unmarked one.
 * - **`ʧ ʤ ʒ`** — `צ׳ ג׳ ז׳`, entirely standard.
 * - **`k`** is `ק` and **`t`** is `ט`: single-valued in Israeli reading, where
 *   `כ` and `ת` are not.
 * - **Nikud on every vowel.** Unpointed Hebrew is unreadable as phonetics, and
 *   this audience — young readers — is the one that still has the vowel points.
 *
 * Two English distinctions are deliberately NOT drawn, because no Hebrew
 * spelling carries them and a false distinction teaches a false sound:
 * `ʊ`/`u` (`book`/`blue`) both become `וּ`, and `ɜɹ`/`ɚ` (`bird`/`butter`)
 * both become `ֶר`. The pronunciation coach is where those get taught.
 *
 * Pure JVM and allocation-light: one pass per word over a handful of phonemes.
 */
object HebrewTransliteration {

    /** One English token and how to say it, ready to stack in the UI. */
    data class GlossWord(val english: String, val hebrew: String)

    /**
     * Pair every word of [text] with its Hebrew-letter pronunciation.
     *
     * Words are phonemized ONE AT A TIME rather than by splitting a whole-line
     * IPA string. The line's own spacing is the voice's prosody, not a column
     * map: normalization can join or split tokens, so a gloss built by
     * splitting that string on spaces would drift out of step with the English
     * words above it — and a gloss whose columns are off by one is worse than
     * no gloss.
     *
     * Punctuation stays on the English side where it belongs; it is not
     * pronounced, so it gets no Hebrew.
     *
     * [phonology] is the accent of the voice that will SAY this line. The
     * gloss is a pronunciation hint, so it has to follow the voice: showing
     * American letters under a line spoken with a burr would teach the child
     * to say something they are not hearing.
     */
    fun gloss(
        text: String,
        phonemizer: KokoroPhonemizer,
        phonology: Phonology = Phonology.GENERAL_AMERICAN,
    ): List<GlossWord> =
        text.split(WHITESPACE).filter { it.isNotBlank() }.map { token ->
            val core = token.trim { it in TRIMMED_PUNCTUATION }
            if (core.isEmpty()) {
                GlossWord(token, "")
            } else {
                GlossWord(token, ofIpa(phonology.applyTo(phonemizer.phonemizeToIpa(core))))
            }
        }

    /**
     * One word's misaki IPA as pointed Hebrew.
     *
     * Syllable assembly, because Hebrew vowel points attach to a consonant
     * rather than standing alone: a consonant is held back until we know
     * whether a vowel follows it, so the point can be written between the
     * letter and any geresh. A vowel with no consonant in front of it — word
     * start, or straight after another vowel — gets `א` to sit on, which is
     * the same thing Hebrew does for `אור` and `אני`.
     */
    /**
     * Can this symbol reach the Hebrew column at all?
     *
     * [ofIpa] drops what it does not recognise, silently and by design — a
     * guessed letter would be worse than a missing one. That makes this the
     * question a caller has to be able to ask BEFORE shipping a phoneme
     * string: an accent emitting a symbol with no entry here loses a sound
     * from the letters the learner reads, and nothing complains. It shipped
     * once exactly that way.
     *
     * Stress marks are not "renderable" and not a loss: Hebrew points carry
     * no stress, so they are silent on purpose.
     */
    fun renders(c: Char): Boolean = c in CONSONANTS || c in VOWELS || c in STRESS_MARKS

    fun ofIpa(ipa: String): String {
        val out = StringBuilder(ipa.length * 2)
        var held: Consonant? = null
        var i = 0
        while (i < ipa.length) {
            val c = ipa[i]
            i++
            if (c in STRESS_MARKS) continue
            val consonant = CONSONANTS[c]
            if (consonant != null) {
                // Two consonants in a row: the first closes its syllable
                // unpointed, exactly as in an unvowelled Hebrew cluster.
                held?.writeInto(out, point = "")
                held = consonant
                continue
            }
            val vowel = VOWELS[c] ?: continue // unknown symbol: drop, never guess
            val onto = held
            if (onto != null) {
                onto.writeInto(out, vowel.point)
                held = null
            } else {
                out.append(CARRIER).append(vowel.point)
            }
            out.append(vowel.trail)
        }
        // Whatever is still held ends the word, which is the only place a
        // final letter form can apply.
        held?.writeInto(out, point = "", final = true)
        return out.toString()
    }

    /**
     * Word-final מ/נ/פ take their final shapes. Only bare single letters
     * qualify, which conveniently excludes every letter we build from two
     * code points — `פּ` (/p/) keeps its dagesh and its ordinary shape, as it
     * does in Hebrew spelling of foreign words.
     */
    private fun finalForm(letter: String, allowed: Boolean): String =
        if (allowed && letter.length == 1) FINALS[letter[0]]?.toString() ?: letter else letter

    /**
     * A consonant and the marks it carries, written in **canonical Unicode
     * order** — which is not the order they are usually described in.
     *
     * The combining classes decide it: vowel points run ccc 10-19 (sheva 10 …
     * holam 19) while dagesh is 21 and the shin dot 24, so normalisation sorts
     * the vowel BEFORE them however they were typed. `ב` + dagesh + segol and
     * `ב` + segol + dagesh look identical on screen and are different strings;
     * only the second survives NFC unchanged, which is what matters the moment
     * anything compares, caches or searches this text. [geresh] is ccc 0 — a
     * starter, not a combining mark — so it comes last, or the marks after it
     * would attach to the geresh instead of to the letter.
     */
    private data class Consonant(
        val letter: String,
        val mark: String = "",
        val geresh: Boolean = false,
    ) {
        fun writeInto(out: StringBuilder, point: String, final: Boolean = false) {
            out.append(finalForm(letter, allowed = final && mark.isEmpty()))
            out.append(point)
            out.append(mark)
            if (geresh) out.append(GERESH)
        }
    }

    /**
     * A vowel as Hebrew writes it: a [point] on the preceding consonant, plus
     * any [trail] letters that carry the sound themselves (the `י` of `ִי`, the
     * `ו` of `וֹ` and `וּ`).
     */
    private data class Vowel(val point: String, val trail: String = "")

    /** Combining marks and letters as escapes: a bare nikud in source is
     *  invisible in most editors and silently mis-ordered by any tool that
     *  normalises the file. */
    private const val CARRIER = "\u05d0"   // alef — what a vowel sits on

    private const val DAGESH = "\u05bc"    // ּ
    private const val SHIN_DOT = "\u05c1"  // ׁ
    private const val GERESH = "\u05f3"    // ׳

    private const val SHEVA = "\u05b0"     // ְ
    private const val HIRIQ = "\u05b4"     // ִ
    private const val TSERE = "\u05b5"     // ֵ
    private const val SEGOL = "\u05b6"     // ֶ
    private const val PATAH = "\u05b7"     // ַ
    private const val HOLAM = "\u05b9"     // ֹ

    private const val YOD = "\u05d9"       // י
    private const val VAV = "\u05d5"       // ו
    private const val RESH = "\u05e8"      // ר

    /** `וֹ` — vav carrying holam. */
    private const val HOLAM_MALE = VAV + HOLAM

    /** `וּ` — vav carrying dagesh. */
    private const val SHURUK = VAV + DAGESH

    private val STRESS_MARKS = setOf('ˈ', 'ˌ')

    private val WHITESPACE = Regex("\\s+")
    private val TRIMMED_PUNCTUATION = ".,!?;:\"'()[]…".toSet()

    private val CONSONANTS: Map<Char, Consonant> = mapOf(
        'b' to Consonant("\u05d1", DAGESH),        // bet + dagesh
        'v' to Consonant("\u05d1"),                // bet
        'w' to Consonant(VAV),                     // vav
        'p' to Consonant("\u05e4", DAGESH),        // pe + dagesh
        'f' to Consonant("\u05e4"),                // pe
        'd' to Consonant("\u05d3"),                // dalet
        'ð' to Consonant("\u05d3", geresh = true), // dalet + geresh   the
        't' to Consonant("\u05d8"),                // tet
        'θ' to Consonant("\u05ea", geresh = true), // tav + geresh     think
        'ɡ' to Consonant("\u05d2"),                // gimel
        'ʤ' to Consonant("\u05d2", geresh = true), // gimel + geresh   judge
        'k' to Consonant("\u05e7"),                // qof
        'h' to Consonant("\u05d4"),                // he
        'l' to Consonant("\u05dc"),                // lamed
        'm' to Consonant("\u05de"),                // mem
        'n' to Consonant("\u05e0"),                // nun
        'ŋ' to Consonant("\u05e0\u05d2"),          // nun + gimel     sing
        'ɹ' to Consonant("\u05e8"),                // resh
        // Every r an accent can produce is still a resh to a Hebrew reader.
        // Israeli resh is itself uvular, so ʁ is the closest of the four to
        // the letter as it is actually pronounced here.
        'ɾ' to Consonant("\u05e8"),                // resh — tapped r
        'ɻ' to Consonant("\u05e8"),                // resh — retroflex r
        'r' to Consonant("\u05e8"),                // resh — trilled r
        'ʁ' to Consonant("\u05e8"),                // resh — uvular R
        'β' to Consonant("\u05d1"),                // bet — bilabial v
        's' to Consonant("\u05e1"),                // samekh
        'ʃ' to Consonant("\u05e9", SHIN_DOT),      // shin + shin dot  ship
        'z' to Consonant("\u05d6"),                // zayin
        'ʒ' to Consonant("\u05d6", geresh = true), // zayin + geresh   measure
        'ʧ' to Consonant("\u05e6", geresh = true), // tsadi + geresh   chair
        'j' to Consonant(YOD),                     // yod              yes
    )

    private val VOWELS: Map<Char, Vowel> = mapOf(
        'ɑ' to Vowel(PATAH),                    // father
        'ʌ' to Vowel(PATAH),                    // cup
        'æ' to Vowel(SEGOL),                    // cat
        'ɛ' to Vowel(SEGOL),                    // bed
        'ə' to Vowel(SHEVA),                    // about
        'ɪ' to Vowel(HIRIQ),                    // sit
        'i' to Vowel(HIRIQ, YOD),               // see
        'ɔ' to Vowel("", HOLAM_MALE),           // ball
        'ɒ' to Vowel("", HOLAM_MALE),           // ball, cot-caught merged
        'ʊ' to Vowel("", SHURUK),               // book
        'u' to Vowel("", SHURUK),               // blue
        'ɜ' to Vowel(SEGOL),                    // bird — the ɹ that follows writes itself
        'ɚ' to Vowel(SEGOL, RESH),              // butter
        // The monophthongs an accent can produce where GA has a diphthong:
        // same vowel, without the glide the yod would write.
        'e' to Vowel(TSERE),                    // day, said flat
        'o' to Vowel("", HOLAM_MALE),           // go, said flat
        'a' to Vowel(PATAH),                    // low central TRAP
        'ɐ' to Vowel(PATAH),                    // unreduced schwa
        'œ' to Vowel(SEGOL),                    // sœur — nearest Hebrew vowel
        'A' to Vowel(TSERE, YOD),               // day  (eɪ)
        'I' to Vowel(PATAH, YOD),               // my   (aɪ)
        'W' to Vowel(PATAH, SHURUK),            // now  (aʊ)
        'O' to Vowel("", HOLAM_MALE),           // go   (oʊ)
        'Y' to Vowel("", HOLAM_MALE + YOD),     // boy  (ɔɪ)
    )

    private val FINALS: Map<Char, Char> = mapOf(
        '\u05de' to '\u05dd', // mem → final mem
        '\u05e0' to '\u05df', // nun → final nun
        '\u05e4' to '\u05e3', // pe  → final pe
    )
}
