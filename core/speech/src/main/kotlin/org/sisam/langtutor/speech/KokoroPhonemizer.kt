package org.sisam.langtutor.speech

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
) {

    /** Token IDs WITHOUT the surrounding BOS/EOS zeros (the engine adds them). */
    fun phonemize(text: String): IntArray {
        val out = ArrayList<Int>(text.length * 2)
        val spaceId = vocab[" "]
        var previousWasWord = false
        for (token in tokenize(KokoroTextNormalizer.normalize(text))) {
            if (token.length == 1 && token[0] in PUNCTUATION) {
                vocab[token]?.let { out.add(it) }
                previousWasWord = false
                continue
            }
            if (previousWasWord && spaceId != null) out.add(spaceId)
            previousWasWord = true
            val arpabet = cmu[token.lowercase()] ?: RuleG2p.toArpabet(token)
            appendArpabet(arpabet, out)
        }
        return out.toIntArray()
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

    private fun appendArpabet(arpabet: String, out: MutableList<Int>) {
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
                1 -> vocab["ˈ"]?.let { out.add(it) }
                2 -> vocab["ˌ"]?.let { out.add(it) }
            }
            for (c in ipa) vocab[c.toString()]?.let { out.add(it) }
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
