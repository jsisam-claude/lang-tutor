package org.sisam.langtutor.speech

/**
 * Text in one language → Kokoro phoneme ids.
 *
 * Kokoro is language-agnostic below the front end: every export shares one
 * 114-symbol IPA vocabulary, so what changes between English and Hebrew is
 * which grapheme-to-phoneme stage produces the IPA, not how it is encoded or
 * what the model does with it.
 */
fun interface KokoroFrontEnd {
    fun phonemize(text: String): IntArray
}

/**
 * English text → Kokoro phoneme-token IDs, the front-end of the bundled Kokoro
 * TTS voice (StyleTTS2-family; the model consumes misaki-style IPA tokens, not
 * graphemes). Fully offline, pure JVM:
 *
 *  1. [KokoroTextNormalizer] expands digits/currency/symbols into words so they
 *     are spoken instead of silently dropped.
 *  2. In-dictionary words use the CMU Pronouncing Dictionary (bundled, BSD;
 *     exact pronunciations WITH stress — stress placement is what makes the
 *     voice sound natural).
 *  3. Out-of-dictionary words (mostly the children's names: Noa, Itai…) fall
 *     back to [RuleG2p] letter-to-sound rules — approximate but always
 *     speakable, never dropped.
 *
 * ARPABET phones map to Kokoro's IPA-ish vocab (114 tokens, pinned from the
 * base model config by scripts/gen-kokoro-frontend-data.sh). Misaki writes the
 * common diphthongs as single uppercase letters (A=/eɪ/, I=/aɪ/, W=/aʊ/,
 * Y=/ɔɪ/, O=/oʊ/) and puts stress marks BEFORE the stressed vowel.
 *
 * Known simplification (fine for short tutor lines): no POS/context
 * disambiguation, so heteronyms ("read", "lives") get their dictionary-first
 * pronunciation.
 */

class KokoroPhonemizer private constructor(
    private val cmu: Map<String, String>,
    private val vocab: Map<String, Int>,
) : KokoroFrontEnd {

    /** Token IDs WITHOUT the surrounding BOS/EOS zeros (the engine adds them). */
    override fun phonemize(text: String): IntArray = encode(phonemizeToIpa(text))

    /**
     * IPA string → Kokoro token ids.
     *
     * Split out from [phonemize] because the ENGLISH G2P above is the only
     * English-specific part of this class: the Hebrew voice is the same Kokoro
     * architecture with a byte-identical vocab, so it reaches the model
     * through this same encoder with Phonikud supplying the IPA instead of
     * CMUdict. Unknown symbols are dropped, as they always were.
     */
    /**
     * Characters of [ipa] this vocabulary cannot carry — empty means the whole
     * string survives [encode] intact.
     *
     * Exists for one reason: the Hebrew voice reaches the model through the
     * same encoder, and [encode] drops what it does not recognise SILENTLY. A
     * front end that emitted, say, ASCII `g` where the vocabulary holds script
     * `ɡ` would lose every hard-g in Hebrew and simply sound wrong. This turns
     * that into an assertion.
     */
    fun unsupported(ipa: String): Set<Char> =
        ipa.filterNot { it.toString() in vocab }.toSet()

    fun encode(ipa: String): IntArray {
        val out = ArrayList<Int>(ipa.length)
        for (c in ipa) vocab[c.toString()]?.let { out.add(it) }
        return out.toIntArray()
    }

    /**
     * The same pronunciation as [phonemize] but as misaki IPA text. Used by
     * pronunciation scoring, which needs the expected SOUNDS (not the voice
     * model's token ids) so the lesson and the scorer agree on what a word is
     * made of.
     */
    fun phonemizeToIpa(text: String): String {
        val sb = StringBuilder(text.length * 2)
        var previousWasWord = false
        for (token in tokenize(KokoroTextNormalizer.normalize(text))) {
            if (token.length == 1 && token[0] in PUNCTUATION) {
                sb.append(token)
                previousWasWord = false
                continue
            }
            if (previousWasWord) sb.append(' ')
            previousWasWord = true
            // Hyphenated compounds miss the dictionary as a whole ("well-done")
            // — phonemize the parts and speak them joined, keeping any direct
            // dictionary hit (CMU does carry some hyphenated entries).
            val arpabet = cmu[token.lowercase()]
                ?: token.split('-').filter { it.isNotEmpty() }
                    .joinToString(" ") { part -> cmu[part.lowercase()] ?: RuleG2p.toArpabet(part) }
            appendArpabet(arpabet, sb)
        }
        return sb.toString()
    }

    /** Words (letters/apostrophes/hyphens) and known punctuation, in order. */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        for (c in text) {
            if (c.isLetter() || c == '\'' || c == '-') {
                word.append(c)
            } else {
                if (word.isNotEmpty()) {
                    tokens.add(word.toString())
                    word.clear()
                }
                if (c in PUNCTUATION) tokens.add(c.toString())
            }
        }
        if (word.isNotEmpty()) tokens.add(word.toString())
        return tokens
    }

    private fun appendArpabet(arpabet: String, out: StringBuilder) {
        for (symbol in arpabet.split(' ')) {
            if (symbol.isEmpty()) continue
            val stress = symbol.last().digitToIntOrNull() ?: -1
            val base = if (stress >= 0) symbol.dropLast(1) else symbol
            val ipa = when (base) {
                // Two reduced/full pairs depend on the stress digit.
                "AH" -> if (stress >= 1) "ʌ" else "ə"
                "ER" -> if (stress >= 1) "ɜɹ" else "ɚ"
                else -> ARPABET_TO_IPA[base] ?: continue
            }
            when (stress) {
                1 -> out.append('ˈ')
                2 -> out.append('ˌ')
            }
            out.append(ipa)
        }
    }

    companion object {
        private val PUNCTUATION = ".,!?;:".toSet()

        /**
         * ARPABET → Kokoro/misaki IPA. Standard phonetic correspondences; the
         * uppercase letters are misaki's single-char diphthongs. AH/ER are
         * handled inline (stress-dependent).
         */
        private val ARPABET_TO_IPA = mapOf(
            "AA" to "ɑ", "AE" to "æ", "AO" to "ɔ", "AW" to "W", "AY" to "I",
            "B" to "b", "CH" to "ʧ", "D" to "d", "DH" to "ð", "EH" to "ɛ",
            "EY" to "A", "F" to "f", "G" to "ɡ", "HH" to "h", "IH" to "ɪ",
            "IY" to "i", "JH" to "ʤ", "K" to "k", "L" to "l", "M" to "m",
            "N" to "n", "NG" to "ŋ", "OW" to "O", "OY" to "Y", "P" to "p",
            "R" to "ɹ", "S" to "s", "SH" to "ʃ", "T" to "t", "TH" to "θ",
            "UH" to "ʊ", "UW" to "u", "V" to "v", "W" to "w", "Y" to "j",
            "Z" to "z", "ZH" to "ʒ",
        )

        /** Loads the bundled dictionary + vocab from module resources. */
        fun load(): KokoroPhonemizer {
            val loader = KokoroPhonemizer::class.java.classLoader
                ?: error("no classloader for Kokoro resources")

            val cmu = HashMap<String, String>(140_000)
            loader.getResourceAsStream("kokoro/cmudict.txt")
                ?.bufferedReader(Charsets.UTF_8)
                ?.useLines { lines ->
                    for (line in lines) {
                        val space = line.indexOf(' ')
                        if (space > 0) cmu[line.substring(0, space)] = line.substring(space + 1)
                    }
                } ?: error("kokoro/cmudict.txt missing from resources")

            val vocab = HashMap<String, Int>(128)
            loader.getResourceAsStream("kokoro/vocab.tsv")
                ?.bufferedReader(Charsets.UTF_8)
                ?.useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        // The phoneme char is AFTER the tab and may itself be a space.
                        if (tab > 0) vocab[line.substring(tab + 1)] = line.substring(0, tab).toInt()
                    }
                } ?: error("kokoro/vocab.tsv missing from resources")

            check(vocab.size == 114) { "Kokoro vocab expected 114 entries, got ${vocab.size}" }
            return KokoroPhonemizer(cmu, vocab)
        }
    }
}
