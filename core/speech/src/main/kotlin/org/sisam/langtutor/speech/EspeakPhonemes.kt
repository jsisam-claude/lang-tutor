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
        'ɔ' to listOf("ɔ", "ɑː", "ɒ"),
        'ɑ' to listOf("ɑː", "ɑ", "ɒ"),
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
        for (c in misakiPhonemes) {
            if (c == 'ˈ' || c == 'ˌ' || c.isWhitespace() || c in ".,!?;:") continue
            val candidates = CANDIDATES[c] ?: listOf(c.toString())
            val hit = candidates.firstNotNullOfOrNull { token ->
                vocab[token]?.let { token to it }
            } ?: continue
            out.add(Expected(label = hit.first, id = hit.second))
        }
        return out
    }
}
