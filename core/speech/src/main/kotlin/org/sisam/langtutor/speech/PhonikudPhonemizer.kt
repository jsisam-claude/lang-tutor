package org.sisam.langtutor.speech

import java.text.Normalizer

/**
 * Pointed-Hebrew → IPA phonemizer — a line-faithful Kotlin port of phonikud's
 * rule engine (github.com/thewh1teagle/phonikud, MIT), the front-end of the
 * bundled Hebrew voice. Input is Hebrew WITH niqqud plus phonikud's extra
 * marks (stress U+05AB, vocal-shva U+05BD, prefix '|'), as produced by the
 * on-device nikud model + [NikudRestorer]; output is the phoneme string the
 * Piper voice consumes via [PiperPhonemes].
 *
 * Deliberately bug-compatible with the Python reference (same rule order,
 * same dead branches) — equivalence is enforced by golden tests generated
 * from the reference on the same inputs. No number/date expander in this
 * port: Tuki's Hebrew lines are authored text, digits pass through unspoken.
 */
object PhonikudPhonemizer {

    private val L = PhonikudLexicon

    /** One Hebrew letter plus its trailing marks; mutable like the reference. */
    internal class Letter(char: String, diac: String) {
        val char: String = normalize(char)
        var allDiac: String = normalize(diac)
        val diac: String
            get() = allDiac.filter { it != L.HATAMA && it != L.PREFIX }

        override fun toString(): String = char + allDiac
    }

    fun phonemize(text: String): String {
        val additional = mutableSetOf<Char>()
        // Digits become pointed Hebrew number words BEFORE anything else, so
        // "3 כדורים" is spoken; out-of-range numbers stay digits and are
        // dropped by postClean exactly as before. (Our own integration rule —
        // the reference's expander also rewrites dates/times, not ported.)
        var t = INTEGER.replace(text) { m ->
            HebrewNumbers.toPointedWords(m.value.toIntOrNull() ?: -1) ?: m.value
        }
        t = normalize(t)

        // Hebrew runs → phonemes (skipping "[word]" hyper-phoneme heads).
        t = L.HE_PATTERN.replace(t) { m ->
            if (m.range.first > 0 && t[m.range.first - 1] == '[') m.value
            else phonemizeHebrewWord(m.value)
        }

        // Hyper phonemes: "[shalom](/ʃalˈom/)" → "ʃalˈom" verbatim.
        t = Regex("""\[(.+?)]\(/(.+?)/\)""").replace(t) { m ->
            m.groupValues[2].also { it.forEach(additional::add) }
        }

        return postClean(t, additional)
    }

    private fun phonemizeHebrewWord(word: String): String {
        var w = word
        // The reference calls its vocal-shva marker here but DISCARDS the
        // result (upstream quirk kept for parity); the meteg comes from the
        // nikud model instead.
        if (L.HATAMA !in w) w = addMilraHatama(w)
        val letters = sortHatama(getLetters(w))
        var phonemes = phonemizeWord(letters).joinToString("")
        phonemes = postNormalize(phonemes)
        for ((from, to) in L.MODERN_SCHEMA) phonemes = phonemes.replace(from, to)
        return phonemes
    }

    // ---------------------------------------------------------------- text

    /** NFD-decompose, per-letter-sort the marks, fold Hebrew geresh/makaf. */
    internal fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFD)
        t = Regex("(\\p{L})(\\p{M}+)").replace(t) { m ->
            m.groupValues[1] + m.groupValues[2].toCharArray().sorted().joinToString("")
        }
        return t.replace('״', '"').replace('׳', '\'').replace('־', '-')
    }

    private val LETTERS_PATTERN = Regex("(\\p{L})([\\p{M}'|]*)")
    private val INTEGER = Regex("\\d+")

    internal fun getLetters(word: String): MutableList<Letter> =
        LETTERS_PATTERN.findAll(word)
            .map { Letter(it.groupValues[1], it.groupValues[2]) }
            .toMutableList()

    // ------------------------------------------------------------- stress

    /** Move a stress mark that sits on a silent (masora) letter to the next one. */
    private fun sortHatama(letters: MutableList<Letter>): MutableList<Letter> {
        for (i in 0 until letters.size - 1) {
            val d = letters[i].allDiac
            if (L.HATAMA in d && L.NIKUD_HASER in d) {
                letters[i].allDiac = d.filter { it != L.HATAMA }
                letters[i + 1].allDiac += L.HATAMA
            }
        }
        return letters
    }

    /** Default stress on the last syllable (milra) when the model marked none. */
    internal fun addMilraHatama(word: String): String {
        val syllables = getSyllables(word)
        if (syllables.isEmpty()) return word
        val idx = if (syllables.size == 1) 0 else syllables.size - 1
        val letters = getLetters(syllables[idx])
        letters[0].allDiac += L.HATAMA
        return buildString {
            for (i in syllables.indices) {
                if (i == idx) letters.forEach { append(it.toString()) }
                else append(syllables[i])
            }
        }
    }

    /** Pointed-word syllable split, with the reference's vav lookahead quirks. */
    internal fun getSyllables(word: String): List<String> {
        val letters = getLetters(word)
        val syllables = mutableListOf<String>()
        var cur = ""
        var vowelState = false
        var i = 0
        while (i < letters.size) {
            val letter = letters[i]
            val hasVowel = hasVowelDiacs(letter) || (i == 0 && L.SHVA in letter.allDiac)
            val vav1 = i + 2 < letters.size && letters[i + 2].char == "ו"
            val vav2 = i + 3 < letters.size && letters[i + 3].char == "ו"

            if (hasVowel) {
                if (vowelState) {
                    syllables.add(cur)
                    cur = letter.toString()
                } else {
                    cur += letter.toString()
                }
                vowelState = true
            } else {
                cur += letter.toString()
            }

            i += 1

            if (vav1 && vav2) {
                if (cur.isNotEmpty()) {
                    syllables.add(cur + letters[i].toString())
                }
                cur = letters[i + 1].toString() + letters[i + 2].toString()
                i += 3
                vowelState = true
            } else if (vav1 && letters[i + 1].diac.isNotEmpty()) {
                if (cur.isNotEmpty()) {
                    syllables.add(cur)
                    cur = ""
                }
                vowelState = false
            }
        }
        if (cur.isNotEmpty()) syllables.add(cur)
        return syllables
    }

    private fun hasVowelDiacs(letter: Letter): Boolean {
        val s = letter.toString()
        if (s == "ו" + L.DAGESH) return true // shuruk
        return s.any { it in L.VOWEL_DIACS }
    }

    /** Within one letter's phoneme chunks, put ˈ right before the first vowel. */
    private fun sortStress(chunks: List<String>): List<String> {
        val text = chunks.joinToString("")
        if (L.STRESS_PHONEME !in text || text.none { it in "aeiou" }) return chunks
        val stripped = chunks.map { it.replace(L.STRESS_PHONEME.toString(), "") }.toMutableList()
        for (i in stripped.indices) {
            val v = stripped[i].indexOfFirst { it in "aeiou" }
            if (v >= 0) {
                stripped[i] = stripped[i].substring(0, v) + L.STRESS_PHONEME + stripped[i].substring(v)
                break
            }
        }
        return stripped
    }

    // ----------------------------------------------------- letter machine

    internal fun phonemizeWord(letters: List<Letter>): List<String> {
        val phonemes = mutableListOf<String>()
        var i = 0
        while (i < letters.size) {
            val prev = letters.getOrNull(i - 1)
            val nxt = letters.getOrNull(i + 1)
            val (p, skip) = phonemizeLetter(letters[i], prev, nxt)
            phonemes += p
            i += skip + 1
        }
        return phonemes
    }

    private fun stressOf(cur: Letter): List<String> =
        if (L.HATAMA in cur.allDiac) listOf(L.STRESS_PHONEME.toString()) else emptyList()

    private fun vowelsOf(cur: Letter): List<String> =
        cur.allDiac.map { L.NIKUD_PHONEMES[it] ?: "" }

    private fun clean(out: List<String>): List<String> =
        out.filter { it.isNotEmpty() && it.all { c -> c in L.PHONEME_CHARS } }

    private fun out(cur: Letter, con: String, vow: List<String>? = null, skip: Int = 0): Pair<List<String>, Int> {
        val chunks = (if (con.isNotEmpty()) listOf(con) else emptyList()) + (vow ?: vowelsOf(cur))
        return clean(sortStress(chunks)) to skip
    }

    private fun phonemizeLetter(cur: Letter, prev: Letter?, nxt: Letter?): Pair<List<String>, Int> {
        val d = cur.diac
        val ch = cur.char
        val s = stressOf(cur)

        // Silent letter (nikud haser mark)
        if (L.NIKUD_HASER in cur.allDiac) return emptyList<String>() to 0

        // Geresh loanword consonant (tav-geresh also swallows the vowels)
        if ('\'' in d && ch in L.GERESH_PHONEMES) {
            return out(cur, L.GERESH_PHONEMES.getValue(ch), vow = if (ch == "ת") emptyList() else null)
        }

        // Beged-kefet dagesh
        if (L.DAGESH in d && (ch + L.DAGESH) in L.LETTER_PHONEMES) {
            return out(cur, L.LETTER_PHONEMES.getValue(ch + L.DAGESH))
        }

        // Vav
        if (ch == "ו" && L.NIKUD_HASER !in cur.allDiac) return vav(cur, prev, nxt)

        // Shin/Sin
        if (ch == "ש") return shin(cur, prev, nxt)

        // Patah gnuva
        if (nxt == null && L.PATAH in d && ch in L.PATAH_GNUVA) {
            return out(cur, L.PATAH_GNUVA.getValue(ch), vow = s)
        }

        // Kamatz before hataf-kamatz sounds 'o'
        if (L.KAMATZ in d && nxt != null && L.HATAF_KAMATZ in nxt.diac) {
            return out(cur, L.LETTER_PHONEMES[ch].orEmpty(), vow = listOf("o") + s)
        }

        // Em kriah — silent bare alef mid-word (not before vav)
        if (ch == "א" && d.isEmpty() && prev != null && nxt != null && nxt.char != "ו") {
            return out(cur, "")
        }

        // Yud kriah — silent bare yud mid-word
        if (ch == "י" && d.isEmpty() && prev != null && nxt != null &&
            prev.char + prev.diac != "א" + L.TSERE &&
            !(nxt.char == "ו" && nxt.diac.isNotEmpty() && L.SHVA !in nxt.diac)
        ) {
            return out(cur, "")
        }

        return out(cur, L.LETTER_PHONEMES[ch].orEmpty())
    }

    private fun shin(cur: Letter, prev: Letter?, nxt: Letter?): Pair<List<String>, Int> {
        if (L.SIN_DOT in cur.diac) {
            if (nxt != null && nxt.char == "ש" && nxt.diac.isEmpty() && L.PATAH_LIKE.containsMatchIn(cur.diac)) {
                return out(cur, "sa", vow = stressOf(cur), skip = 1)
            }
            return out(cur, "s")
        }
        if (cur.diac.isEmpty() && prev != null && L.SIN_DOT in prev.diac) {
            return out(cur, "s")
        }
        return out(cur, L.LETTER_PHONEMES.getValue("ש"))
    }

    private fun vavVowel(d: String): String? = when {
        L.PATAH_LIKE.containsMatchIn(d) -> "va"
        L.TSERE in d || L.SEGOL in d || L.VOCAL_SHVA in d -> "ve"
        L.HOLAM in d -> "o"
        L.KUBUTS in d || L.DAGESH in d -> "u"
        L.HIRIK in d -> "vi"
        else -> null
    }

    private fun vav(cur: Letter, prev: Letter?, nxt: Letter?): Pair<List<String>, Int> {
        val d = cur.diac
        val s = stressOf(cur)
        if (prev != null && L.SHVA in prev.diac && L.HOLAM in d) {
            return out(cur, "vo", vow = s)
        }
        if (nxt != null && nxt.char == "ו") {
            val dd = d + nxt.diac
            if (L.HOLAM in dd) return out(cur, "vo", vow = s, skip = 1)
            if (d == nxt.diac) return out(cur, "vu", vow = s, skip = 1)
            vavVowel(d)?.let { return out(cur, it, vow = s) }
            if (L.SHVA in d && nxt.diac.isEmpty()) return out(cur, "v", vow = s)
            return out(cur, "", vow = s)
        }
        vavVowel(d)?.let { return out(cur, it, vow = s) }
        if (L.SHVA in d && prev == null) return out(cur, "ve", vow = s)
        if (nxt != null && d.isEmpty()) return out(cur, "", vow = s)
        return out(cur, "v", vow = s)
    }

    // ------------------------------------------------------------ output

    /** Word-final cleanups (glottal stop, mute he, ij→i), reference order. */
    private fun postNormalize(phonemes: String): String =
        phonemes.split(" ").joinToString(" ") { word ->
            word
                .replace(Regex("ʔ$"), "")
                .replace(Regex("h$"), "")
                .replace(Regex("ˈh$"), "")
                .replace(Regex("ij$"), "i")
        }

    private fun postClean(phonemes: String, additional: Set<Char>): String =
        buildString {
            for (c in phonemes) {
                when {
                    c == '-' -> append(' ')
                    c in L.PHONEME_CHARS || c in additional || c == ' ' || c in L.PUNCTUATION -> append(c)
                }
            }
        }
}
