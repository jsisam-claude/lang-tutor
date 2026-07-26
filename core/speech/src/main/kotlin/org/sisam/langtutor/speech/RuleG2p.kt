package org.sisam.langtutor.speech

/**
 * Last-resort letter-to-sound rules for words the CMU dictionary doesn't know —
 * in this app that means mostly the children's names (Noa, Yael, Itai…), which
 * MUST be spoken, even approximately, never dropped or spelled out.
 *
 * Greedy longest-match over common English digraphs, then single letters;
 * primary stress goes on the first vowel (the usual pattern for short names).
 * Output is stress-annotated ARPABET, same format as the dictionary values.
 * Approximate by design: "Noa" → "N OW1" is imperfect but warm and clear,
 * which beats silence or letter-names.
 */
object RuleG2p {

    fun toArpabet(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val phones = mutableListOf<String>()
        var i = 0
        while (i < w.length) {
            // Word-final silent e (make, Rose) — skip it after a consonant.
            if (w[i] == 'e' && i == w.length - 1 && i >= 2 && w[i - 1] !in VOWELS) {
                i++
                continue
            }
            val pair = if (i + 1 < w.length) w.substring(i, i + 2) else ""
            val digraph = DIGRAPHS[pair]
            if (digraph != null) {
                phones.add(digraph)
                i += 2
                continue
            }
            // Collapse doubled consonants (Anna → single N).
            if (pair.length == 2 && pair[0] == pair[1] && pair[0] !in VOWELS) {
                i++
                continue
            }
            val c = w[i]
            // y: consonant /j/ at word start before a vowel (Yael), vowel /i/ elsewhere.
            val phone = if (c == 'y') {
                if (i == 0 && i + 1 < w.length && w[i + 1] in VOWELS) "Y" else "IY"
            } else {
                SINGLES[c]
            }
            if (phone != null) phones.add(phone)
            i++
        }
        // Primary stress on the first vowel phone; later vowels unstressed.
        var stressed = false
        return phones.joinToString(" ") { p ->
            if (p in VOWEL_PHONES) {
                val digit = if (stressed) "0" else "1"
                stressed = true
                "$p$digit"
            } else p
        }
    }

    private val VOWELS = "aeiou".toSet()

    private val DIGRAPHS = mapOf(
        "ch" to "CH", "sh" to "SH", "th" to "TH", "ph" to "F", "wh" to "W",
        "ng" to "NG", "ck" to "K", "qu" to "K W",
        "oo" to "UW", "ee" to "IY", "ea" to "IY", "ai" to "EY", "ay" to "EY",
        "oa" to "OW", "ou" to "AW", "ow" to "OW", "oy" to "OY", "oi" to "OY",
        "au" to "AO", "aw" to "AO", "ei" to "EY", "ie" to "IY", "ue" to "UW",
    )

    private val SINGLES = mapOf(
        'a' to "AE", 'e' to "EH", 'i' to "IH", 'o' to "AA", 'u' to "AH",
        'b' to "B", 'c' to "K", 'd' to "D", 'f' to "F", 'g' to "G",
        'h' to "HH", 'j' to "JH", 'k' to "K", 'l' to "L", 'm' to "M",
        'n' to "N", 'p' to "P", 'r' to "R", 's' to "S", 't' to "T",
        'v' to "V", 'w' to "W", 'x' to "K S", 'z' to "Z",
    )

    private val VOWEL_PHONES = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY",
        "IH", "IY", "OW", "OY", "UH", "UW",
    )
}
