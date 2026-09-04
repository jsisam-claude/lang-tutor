package org.sisam.langtutor.speech

/**
 * Bridges the phonemes we already produce for a lesson word to the vocabulary
 * of the bundled phoneme-CTC model (eSpeak IPA, 392 tokens, pinned to
 * resources). The tutor knows the expected pronunciation of every lesson word
 * through [KokoroPhonemizer]'s CMU→misaki mapping; the scorer needs the same
 * sounds expressed as model vocabulary ids.
 *
 * Two mismatches to absorb:
 *  - misaki writes diphthongs as single uppercase letters (A=eɪ, I=aɪ, W=aʊ,
 *    O=oʊ, Y=ɔɪ) while eSpeak spells them out;
 *  - the model is multilingual and prefers certain realizations, e.g. it hears
 *    "ball" as bɑːl rather than bɔl. Accepting a documented ALTERNATE keeps a
 *    correct child from being marked wrong for the model's accent preference —
 *    the first id that exists in the vocabulary wins.
 */
object EspeakPhonemes {

    /** Vocabulary token → id, from the model's own vocab.json (pinned). */
    val vocab: Map<String, Int> by lazy {
        val loader = EspeakPhonemes::class.java.classLoader
            ?: error("no classloader for gop resources")
        val map = HashMap<String, Int>(512)
        loader.getResourceAsStream("gop/espeak-vocab.tsv")
            ?.bufferedReader(Charsets.UTF_8)
            ?.useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(tab + 1)] = line.substring(0, tab).toInt()
                }
            } ?: error("gop/espeak-vocab.tsv missing from resources")
        check(map.size > 300) { "espeak vocab looks truncated: ${map.size}" }
        map
    }

    val blankId: Int by lazy { vocab["<pad>"] ?: 0 }

    /**
     * Candidate eSpeak spellings per phoneme, best first. Diphthongs list the
     * single eSpeak token first and a fallback nucleus second, so a word still
     * scores if the model tokenizes it differently.
     */
    private val CANDIDATES: Map<Char, List<String>> = mapOf(
        // misaki single-char diphthongs
        'A' to listOf("eɪ", "e"),
        'I' to listOf("aɪ", "a"),
        'W' to listOf("aʊ", "a"),
        'O' to listOf("oʊ", "əʊ", "o"),
        'Y' to listOf("ɔɪ", "ɔ"),
        // vowels where the model's preferred realization differs
        'ɔ' to listOf("ɔː", "ɔ", "ɑː", "ɒ"),
        'ɑ' to listOf("ɑː", "ɑ", "ɒ"),
        // Length is phonemic in the model's inventory and it was being asked
        // for the wrong one: `i` is the happY vowel and `u` its back
        // counterpart, while FLEECE and GOOSE are `iː` and `uː`. Since our
        // front end writes one symbol for both, STRESS decides — see
        // [expectedFrom]. Listed short-first here; the stressed lookup uses
        // [LONG] instead.
        'i' to listOf("i", "iː"),
        'u' to listOf("u", "uː"),
        'ɜ' to listOf("ɜː", "ɜ"),
        'ə' to listOf("ə", "ɐ"),
        'ʌ' to listOf("ʌ", "ɐ"),
        'æ' to listOf("æ", "a"),
        'ɛ' to listOf("ɛ", "e"),
        // consonants: our affricate glyphs vs the model's two-char tokens
        'ʧ' to listOf("tʃ", "ʧ"),
        'ʤ' to listOf("dʒ", "ʤ"),
        'ɹ' to listOf("ɹ", "r"),
        'ɡ' to listOf("ɡ", "g"),
    )

    /**
     * Sequences the model spells as ONE token.
     *
     * Our front end writes an r-coloured vowel as a vowel plus a separate
     * /ɹ/, and a syllabic l as a schwa plus an l, because that is how misaki
     * writes them. The coach model does not: its vocabulary carries ɑːɹ, ɔːɹ,
     * ɪɹ, ɛɹ, ʊɹ and əl as single phones, and those tokens exist precisely
     * because it was trained on transcriptions that use them. Asking for two
     * phones where the model produces one puts the whole alignment out by a
     * frame for the rest of the word — so "car", "four", "ear", "air" and
     * every -le word were being scored against a target the audio could never
     * match.
     *
     * Matched longest-first, before the per-character walk.
     */
    private val SEQUENCES: List<Pair<String, String>> = listOf(
        "ɑɹ" to "ɑːɹ",
        // The NEAR set reaches us both ways — CMUdict writes "ear" IY1 R and
        // "beer" IH1 R for the same vowel — and the model spells both ɪɹ.
        "iɹ" to "ɪɹ",
        "ɔɹ" to "ɔːɹ",
        "ɪɹ" to "ɪɹ",
        "ɛɹ" to "ɛɹ",
        "ʊɹ" to "ʊɹ",
        "əl" to "əl",
    )

    /**
     * What a STRESSED vowel should be asked for instead.
     *
     * ARPABET writes one symbol for a pair the model keeps apart: IY is both
     * FLEECE (see, sheep — long) and happY (unstressed — short), and UW is
     * both GOOSE and its unstressed counterpart. Targeting the short one for
     * every occurrence scored the ship/sheep drill against the wrong sound,
     * which matters here more than almost anywhere: that contrast is one of
     * the fifteen the tongue-twister room exists to teach.
     */
    private val LONG: Map<Char, List<String>> = mapOf(
        'i' to listOf("iː", "i"),
        'u' to listOf("uː", "u"),
    )

    /** A phoneme of the utterance, ready to score. */
    data class Expected(val label: String, val id: Int)

    /**
     * Maps a misaki phoneme string (KokoroPhonemizer output for the expected
     * word) to scorable phonemes. Stress marks, spaces and punctuation are
     * dropped — they carry no segmental pronunciation to score. Sounds with no
     * vocabulary entry at all are skipped rather than scored as failures.
     */
    fun expectedFrom(misakiPhonemes: String): List<Expected> {
        val out = mutableListOf<Expected>()
        var stressed = false
        var i = 0
        while (i < misakiPhonemes.length) {
            // A sequence the model spells as one phone wins over the letters
            // it is made of — see [SEQUENCES].
            val seq = SEQUENCES.firstOrNull { misakiPhonemes.startsWith(it.first, i) }
            if (seq != null) {
                vocab[seq.second]?.let { out.add(Expected(seq.second, it)) }
                i += seq.first.length
                stressed = false
                continue
            }
            val c = misakiPhonemes[i]
            i++
            if (c == 'ˈ' || c == 'ˌ') {
                // The mark precedes the vowel it belongs to, so it is still
                // true when that vowel is reached and false again after it.
                stressed = true
                continue
            }
            if (c.isWhitespace() || c in ".,!?;:") continue
            val candidates = (if (stressed) LONG[c] else null)
                ?: CANDIDATES[c]
                ?: listOf(c.toString())
            stressed = false
            val hit = candidates.firstNotNullOfOrNull { token ->
                vocab[token]?.let { token to it }
            } ?: continue
            out.add(Expected(label = hit.first, id = hit.second))
        }
        return out
    }

    /**
     * KNOWN LIMITS, deliberately not guessed at.
     *
     * Three more places where our phone string and the model's differ, all
     * CONTEXT-dependent, so none can be fixed by a symbol table and none is
     * fixed here:
     *
     *  - American flapping. espeak writes the /t/ of "water" as ɾ (id 15);
     *    we ask for t, so every intervocalic t after a stressed vowel is
     *    scored against a sound the model did not emit. The rule is
     *    positional, not lexical.
     *  - The reduced vowel ᵻ (id 50) in "-ed" and "-es" endings, which is
     *    exactly the sound the -ed twisters drill. We ask for ɪ, which is
     *    also a real vowel elsewhere, so it cannot be remapped wholesale.
     *  - Stressed NURSE. We emit ɜɹ and ask for ɜː + ɹ; whether the model
     *    puts a separate ɹ there is not something this repository can settle
     *    without running it on a device.
     *
     * All three want a recording and a look at the posteriors, not a guess.
     */
}
