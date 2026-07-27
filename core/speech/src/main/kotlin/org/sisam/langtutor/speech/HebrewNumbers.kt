package org.sisam.langtutor.speech

/**
 * Integers → pointed Hebrew number words, so digits inside Hebrew tutor lines
 * are SPOKEN ("3 כדורים" → "שָׁלוֹשׁ כדורים") instead of silently dropped.
 *
 * Two mirrored stages of the phonikud reference (MIT):
 *  1. bare words — the num2words-he feminine-cardinal composition (vav joins
 *     the FIRST word of the LAST component: "מאה ואחת", "שלוש מאות עשרים ואחת");
 *  2. pointing — per-word lookup in the pinned number-names table, with the
 *     reference's prefix fallback (a glued ו/ב letter is pointed separately).
 *
 * Range 0..999 — a child tutor's counting domain; anything larger stays as
 * digits (and is dropped downstream, exactly as before). Parity with the
 * reference is enforced by a golden test over EVERY value in range.
 */
object HebrewNumbers {

    /** Pointed words for [n], or null when out of the supported range. */
    fun toPointedWords(n: Int): String? {
        if (n !in 0..999) return null
        return bareWords(n).split(' ').joinToString(" ") { pointWord(it) }
    }

    // ------------------------------------------------------ bare words

    private val ONES = arrayOf(
        "", "אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע",
    )

    /** In teens the 1/2 forms change: "אחת עשרה" but "שתים עשרה". */
    private val TEEN_ONES = arrayOf(
        "", "אחת", "שתים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע",
    )

    private val TENS = arrayOf(
        "", "עשר", "עשרים", "שלושים", "ארבעים", "חמישים",
        "שישים", "שבעים", "שמונים", "תשעים",
    )

    internal fun bareWords(n: Int): String {
        if (n == 0) return "אפס"
        // Components in speaking order; the vav joins the last one.
        val components = mutableListOf<List<String>>()
        val hundreds = n / 100
        when {
            hundreds == 1 -> components.add(listOf("מאה"))
            hundreds == 2 -> components.add(listOf("מאתיים"))
            hundreds > 2 -> components.add(listOf(ONES[hundreds], "מאות"))
        }
        val rem = n % 100
        when {
            rem == 0 -> Unit
            rem < 10 -> components.add(listOf(ONES[rem]))
            rem == 10 -> components.add(listOf("עשר"))
            rem < 20 -> components.add(listOf(TEEN_ONES[rem - 10], "עשרה"))
            else -> {
                components.add(listOf(TENS[rem / 10]))
                if (rem % 10 != 0) components.add(listOf(ONES[rem % 10]))
            }
        }
        if (components.size > 1) {
            val last = components.last().toMutableList()
            last[0] = "ו" + last[0]
            components[components.size - 1] = last
        }
        return components.flatten().joinToString(" ")
    }

    // -------------------------------------------------------- pointing

    private val pointedByBare: Map<String, String> by lazy {
        val loader = HebrewNumbers::class.java.classLoader
            ?: error("no classloader for phonikud resources")
        val map = HashMap<String, String>(160)
        loader.getResourceAsStream("phonikud/number-names.tsv")
            ?.bufferedReader(Charsets.UTF_8)
            ?.useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            } ?: error("phonikud/number-names.tsv missing from resources")
        check(map.size > 100) { "number-names table looks truncated: ${map.size}" }
        map
    }

    /** Mirror of the reference add_diacritics word rule, including fallbacks. */
    private fun pointWord(word: String): String {
        pointedByBare[word]?.let { return it }
        if (word.length > 1) {
            val prefix = pointedByBare[word.take(1)]
            val rest = pointedByBare[word.substring(1)]
            if (prefix != null && rest != null) return prefix + rest
        }
        return word
    }
}
