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
 * end already produced. Three invariants hold for every accent here, and
 * `PhonologyTest` enforces all three:
 *
 *  1. Every symbol emitted is in Kokoro's 114-token vocabulary, or the
 *     encoder silently deletes it and a sound disappears mid-word.
 *  2. Every symbol emitted is scorable by the pronunciation coach's
 *     392-phone vocabulary, or the child is graded against a shorter
 *     sentence than they heard.
 *  3. Every symbol emitted is renderable by [HebrewTransliteration], or it
 *     vanishes from the Hebrew column the learner reads. This one was learned
 *     the hard way: the first accent shipped emitting e, o, ɾ and ɒ, none of
 *     which the gloss knew, so "red" was glossed אֶד and "bird" came out
 *     identical to "bed".
 *
 * And one rule about which accents may exist at all: **no accent here
 * destroys an English phonemic contrast.** A rewrite that merges think with
 * sink, ship with sheep or three with tree is accurate description and a
 * terrible teacher — worse here than anywhere, because Hebrew shares those
 * gaps, so the app would be modelling the learner's own error back at them.
 * That rules out most of what makes a second-language accent recognisable,
 * which is why the sets below are short and why they say so.
 */
enum class Phonology(
    val rules: List<Rule>,
    /** Where this accent applies. See [Scope]. */
    val scope: Scope = Scope.EVERYWHERE,
) {

    /** What CMUdict gives us, and what every voice but a character speaks. */
    GENERAL_AMERICAN(emptyList()),

    /**
     * Scottish Standard English, as far as a phoneme rewrite reaches.
     *
     * FACE and GOAT as monophthongs rather than the /eɪ/ and /oʊ/ diphthongs
     * misaki writes as A and O, /ɹ/ realised as the tap, and the cot–caught
     * merger. English is rhotic here already, so the r changes quality, not
     * whether it is pronounced, and the tap is the cue an ear catches first.
     */
    SCOTTISH(
        listOf(
            Rule("A", "e", "FACE is a monophthong"),
            Rule("O", "o", "GOAT is a monophthong"),
            Rule("ɹ", "ɾ", "the tapped r"),
            Rule("ɔ", "ɒ", "cot and caught merged"),
        ),
    ),

    /**
     * Southern Irish English.
     *
     * Separated from [SCOTTISH] by the rhotic — supraregional Irish /r/ is a
     * retroflex approximant, not a tap — and by TRAP, which is a low central
     * vowel. TH-stopping is the accent's defining feature and is deliberately
     * absent: real Irish keeps thin and tin apart by dentality, the inventory
     * has no dental diacritic, and a bare t would merge them.
     */
    IRISH(
        listOf(
            Rule("A", "e", "FACE is a monophthong"),
            Rule("O", "o", "GOAT is a monophthong"),
            Rule("ɹ", "ɻ", "the retroflex r, which is what separates this from Scottish"),
            Rule("æ", "a", "TRAP is low and central"),
        ),
    ),

    /**
     * English with an Italian accent.
     *
     * An apical r everywhere, NURSE and lettER resolved into a vowel plus a
     * separate r because Italian has no r-coloured vowel, and TRAP on the
     * Italian low vowel. TH-stopping, the tense–lax mergers and the loss of
     * vowel reduction are all attested and all excluded: each destroys a
     * contrast (three/tree, ship/sheep, full/fool).
     */
    ITALIAN(
        listOf(
            Rule("ɜɹ", "ɜr", "NURSE keeps its own vowel and takes a separate r"),
            Rule("ɚ", "ɜr", "unstressed -er, likewise"),
            Rule("ɹ", "r", "the apical r"),
            Rule("æ", "a", "TRAP on the Italian low vowel"),
        ),
        Scope.VOICE_ONLY,
    ),

    /**
     * English with a French accent.
     *
     * The uvular R, NURSE and STRUT on the front rounded vowel of sœur, and
     * both mid diphthongs flattened. TH-fronting and h-dropping are excluded:
     * they merge think with sink and hair with air.
     */
    FRENCH(
        listOf(
            Rule("ɜɹ", "œʁ", "NURSE on the vowel of sœur, plus a separate R"),
            Rule("ɚ", "œʁ", "unstressed -er, likewise"),
            Rule("ɹ", "ʁ", "the uvular R"),
            Rule("ʌ", "œ", "STRUT has no French home and lands on the same rounded vowel"),
            Rule("A", "e", "FACE is a monophthong"),
            Rule("O", "o", "GOAT is a monophthong"),
        ),
        Scope.VOICE_ONLY,
    ),

    /**
     * English with a Spanish accent.
     *
     * The tapped r, NURSE resolved into vowel plus tap, and /v/ on the
     * bilabial approximant Spanish actually has. Deliberately NOT here:
     * the tense–lax mergers, and z→s, which would merge zoo with Sue.
     */
    SPANISH(
        listOf(
            Rule("ɜɹ", "ɜɾ", "NURSE keeps its own vowel and takes a separate tap"),
            Rule("ɚ", "ɜɾ", "unstressed -er, likewise"),
            Rule("ɹ", "ɾ", "the tapped r"),
            Rule("v", "β", "Spanish has no labiodental /v/; the bilabial keeps v and w apart"),
        ),
        Scope.VOICE_ONLY,
    ),

    /**
     * English with a Hebrew accent — the learners' own.
     *
     * Worth having for exactly that reason: hearing your own accent named and
     * spoken back is not a joke, it is the clearest possible statement of
     * what the lessons are working on. The uvular R is Israeli Hebrew's real
     * resh, and TRAP is low and central because Hebrew has no /æ/.
     *
     * What is NOT here is the whole list of Hebrew-L1 substitutions this app
     * exists to correct: θ→s, ð→d, æ→ɛ, w→v. Modelling those would be the app
     * teaching the error it was built to fix.
     */
    HEBREW(
        listOf(
            Rule("ɜɹ", "ɜʁ", "NURSE keeps its own vowel and takes a separate R"),
            Rule("ɚ", "ɜʁ", "unstressed -er, likewise"),
            Rule("ɹ", "ʁ", "the uvular resh"),
            Rule("æ", "a", "Hebrew has no /æ/; TRAP is low and central"),
        ),
        Scope.VOICE_ONLY,
    ),

    /**
     * English with an Arabic accent.
     *
     * A trilled r, NURSE and lettER resolved into vowel plus r, and both mid
     * diphthongs flattened — Arabic's short vowel system has no glides there.
     * The tense–lax mergers and p→b are excluded: p→b alone would merge over
     * a thousand pairs, pat with bat among them.
     */
    ARABIC(
        listOf(
            Rule("ɜɹ", "ɜr", "NURSE keeps its own vowel and takes a separate r"),
            Rule("ɚ", "ɜr", "unstressed -er, likewise"),
            Rule("ɹ", "r", "the trilled r"),
            Rule("A", "e", "FACE is a monophthong"),
            Rule("O", "o", "GOAT is a monophthong"),
        ),
        Scope.VOICE_ONLY,
    ),

    /**
     * English with a Mandarin accent.
     *
     * The shortest set here, and honestly so. Mandarin's identifying features
     * in English are almost all mergers — TH to the sibilant, the tense–lax
     * collapses — and every one of them is barred. What is left is real and
     * contrast-free: the retroflex r Mandarin actually has, and unstressed
     * vowels that keep their quality instead of reducing to schwa, which is
     * the segmental half of syllable timing.
     */
    MANDARIN(
        listOf(
            Rule("ɹ", "ɻ", "the retroflex r Mandarin has"),
            Rule("ə", "ʌ", "unstressed vowels keep their quality"),
        ),
        Scope.VOICE_ONLY,
    ),
    ;

    /** One substitution, and why it is defensible. */
    data class Rule(val from: String, val to: String, val why: String)

    /**
     * Where an accent is allowed to reach.
     *
     * [EVERYWHERE] is a NATIVE English accent: the pronunciation coach and
     * the Hebrew gloss follow it, so a child hears one accent, is scored
     * against it, and reads letters that match it.
     *
     * [VOICE_ONLY] is a SECOND-LANGUAGE accent, and the difference is not
     * cosmetic. A native accent is a legitimate model of English. An L2
     * accent is a description of someone still learning it, so the coach must
     * keep expecting standard English and the gloss must keep showing it —
     * otherwise the app grades a learner against a learner's approximation
     * and prints it as the target. The character sounds like themselves; what
     * is taught does not move.
     */
    enum class Scope { EVERYWHERE, VOICE_ONLY }

    /**
     * [ipa] as this accent would say it.
     *
     * One pass, longest match first, no cascading: a symbol a rule produces
     * is never re-matched by another rule. That discipline is what lets each
     * rule be read on its own, and it is why the sets above can be checked
     * for collisions mechanically.
     */
    fun applyTo(ipa: String): String {
        if (rules.isEmpty()) return ipa
        val out = StringBuilder(ipa.length)
        var i = 0
        outer@ while (i < ipa.length) {
            for (rule in byLongestFrom) {
                if (ipa.startsWith(rule.from, i)) {
                    out.append(rule.to)
                    i += rule.from.length
                    continue@outer
                }
            }
            out.append(ipa[i])
            i++
        }
        return out.toString()
    }

    private val byLongestFrom: List<Rule> by lazy { rules.sortedByDescending { it.from.length } }

    /** Every symbol this accent can introduce — what the invariants check. */
    val emits: Set<Char> by lazy { rules.flatMap { it.to.toList() }.toSet() }
}
