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

    /**
     * The same, spoken in an accent — see [Phonology], which explains why an
     * accent has to be applied here and not in the voice table.
     *
     * Defaulted rather than abstract because only the English front end has
     * accents to offer: a Scottish Hebrew voice is not a thing, so the Hebrew
     * one correctly ignores the request instead of implementing it twice.
     */
    fun phonemize(text: String, phonology: Phonology): IntArray = phonemize(text)
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

    override fun phonemize(text: String, phonology: Phonology): IntArray =
        encode(phonology.applyTo(phonemizeToIpa(text)))

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
        // A pause is a phoneme here: the vocabulary carries `.,!?;:` and a
        // space, and Kokoro's prosody comes from them. Punctuation binds to the
        // word it follows and is NOT preceded by a space, but the next word
        // still needs its separator — without it "Almost! Listen again."
        // reaches the model as one unbroken run and is spoken as one, which is
        // exactly how the drill's recast lost its beat.
        var needsSpace = false
        for (token in tokenize(KokoroTextNormalizer.normalize(text))) {
            if (token.length == 1 && token[0] in PUNCTUATION) {
                sb.append(token)
                needsSpace = true
                continue
            }
            if (needsSpace) sb.append(' ')
            needsSpace = true
            // Hyphenated compounds miss the dictionary as a whole ("well-done")
            // — phonemize the parts and speak them joined, keeping any direct
            // dictionary hit (CMU does carry some hyphenated entries).
            val arpabet = lookUp(token)
            appendArpabet(arpabet, sb)
        }
        return sb.toString()
    }

    /**
     * Is this word looked up rather than guessed?
     *
     * Exposed so a test can hold the shipped corpus to it: [RuleG2p] is a
     * safety net for a stranger's name, not something a line the app says
     * every session should be relying on.
     */
    fun isKnown(word: String): Boolean {
        val w = word.lowercase()
        if (cmu.containsKey(w) || possessive(w) != null) return true
        val parts = w.split('-').filter { it.isNotEmpty() }
        return parts.size > 1 && parts.all { cmu.containsKey(it) }
    }

    /**
     * One written word to ARPABET, trying every rule that beats a guess.
     *
     * In order: the dictionary (with the exceptions merged over it); the
     * regular possessive, so content does not have to enumerate every
     * "hamster's" it uses; hyphenated compounds part by part, since CMU
     * carries only some of them whole; and only then [RuleG2p], which guesses
     * from spelling and is the reason "Tuki" needed an entry.
     */
    private fun lookUp(token: String): String {
        val word = token.lowercase()
        cmu[word]?.let { return it }
        possessive(word)?.let { return it }
        return token.split('-').filter { it.isNotEmpty() }
            .joinToString(" ") { part -> cmu[part.lowercase()] ?: RuleG2p.toArpabet(part) }
    }

    /**
     * The regular English possessive, built from the base word: /ɪz/ after a
     * sibilant, /s/ after a voiceless consonant, /z/ otherwise. Exactly the
     * rule a speaker applies, so "the grasshopper's legs" no longer depends on
     * anyone having thought to add "grasshopper's" to a file.
     */
    private fun possessive(word: String): String? {
        if (!word.endsWith("'s")) return null
        val base = cmu[word.dropLast(2)] ?: return null
        val last = base.substringAfterLast(' ').trimEnd('0', '1', '2')
        return base + when (last) {
            in SIBILANTS -> " IH0 Z"
            in VOICELESS -> " S"
            else -> " Z"
        }
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

        /** Finals that take the extra syllable in a possessive. */
        private val SIBILANTS = setOf("S", "Z", "SH", "ZH", "CH", "JH")

        /** Finals that devoice it. */
        private val VOICELESS = setOf("P", "T", "K", "F", "TH")

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

            // Merged OVER the dictionary, so an entry here wins: the bundled
            // dictionary keeps one pronunciation per word and for a handful it
            // kept the sense this app never uses ("perfect" as a verb, "wind"
            // that rhymes with "find"), and it does not carry the tutor's own
            // name at all — "Tuki" was being guessed, and came out "Taki".
            loader.getResourceAsStream("kokoro/pronunciation-exceptions.tsv")
                ?.bufferedReader(Charsets.UTF_8)
                ?.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank() || line.startsWith("#")) continue
                        val tab = line.indexOf('\t')
                        if (tab > 0) cmu[line.substring(0, tab).lowercase()] = line.substring(tab + 1).trim()
                    }
                } ?: error("kokoro/pronunciation-exceptions.tsv missing from resources")

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
