package org.sisam.langtutor.speech

import java.text.Normalizer

/**
 * Spoken-form normalization ahead of [KokoroPhonemizer]: digits, currency and
 * symbols become words BEFORE dictionary lookup, so "42" is said "forty two"
 * rather than vanishing as an unpronounceable token. Deliberately small — it
 * covers what a child tutor actually emits (counts, prices in exercises,
 * percent, a few symbols), not general text.
 */
object KokoroTextNormalizer {

    fun normalize(text: String): String {
        // Fold diacritics so accented names keep their letters (José → Jose).
        var t = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        // "covid19" / "3rd-grade" style boundaries become separate tokens.
        t = LETTER_DIGIT_BOUNDARY.replace(t, " ")
        t = DOLLARS.replace(t) { m ->
            val dollars = cardinal(m.groupValues[1].replace(",", "").toLong())
            val cents = m.groupValues[2]
            if (cents.isEmpty()) "$dollars dollars"
            else "$dollars dollars and ${cardinal(cents.padEnd(2, '0').toLong())} cents"
        }
        t = PERCENT.replace(t) { m -> "${spokenNumber(m.groupValues[1])} percent" }
        t = NUMBER.replace(t) { m -> spokenNumber(m.value) }
        for ((symbol, word) in SYMBOL_WORDS) t = t.replace(symbol, word)
        return t.replace(WHITESPACE, " ").trim()
    }

    /** "3.14" → "three point one four"; plain integers → cardinal words. */
    private fun spokenNumber(raw: String): String {
        val s = raw.replace(",", "")
        val dot = s.indexOf('.')
        if (dot < 0) return cardinal(s.toLong())
        val whole = cardinal(s.take(dot).ifEmpty { "0" }.toLong())
        val fraction = s.substring(dot + 1).map { ONES[it - '0'] }.joinToString(" ")
        return "$whole point $fraction"
    }

    private fun cardinal(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "minus ${cardinal(-n)}"
        val parts = StringBuilder()
        var rest = n
        for ((value, name) in SCALES) {
            if (rest >= value) {
                parts.append(upToThousand((rest / value).toInt())).append(' ').append(name).append(' ')
                rest %= value
            }
        }
        if (rest > 0) parts.append(upToThousand(rest.toInt()))
        return parts.toString().trim()
    }

    private fun upToThousand(n: Int): String {
        val sb = StringBuilder()
        if (n >= 100) {
            sb.append(ONES[n / 100]).append(" hundred")
            if (n % 100 != 0) sb.append(' ')
        }
        val rest = n % 100
        when {
            rest in 1..19 -> sb.append(ONES[rest])
            rest >= 20 -> {
                sb.append(TENS[rest / 10])
                if (rest % 10 != 0) sb.append(' ').append(ONES[rest % 10])
            }
        }
        return sb.toString()
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val LETTER_DIGIT_BOUNDARY = Regex("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])")
    private val DOLLARS = Regex("\\$\\s?(\\d+(?:,\\d{3})*)(?:\\.(\\d{1,2}))?")
    private val PERCENT = Regex("(\\d+(?:\\.\\d+)?)\\s?%")
    private val NUMBER = Regex("\\d+(?:,\\d{3})*(?:\\.\\d+)?")
    private val WHITESPACE = Regex("\\s+")

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )
    private val SCALES = listOf(
        1_000_000_000L to "billion",
        1_000_000L to "million",
        1_000L to "thousand",
    )
    private val SYMBOL_WORDS = listOf(
        "&" to " and ", "%" to " percent ", "@" to " at ",
        "+" to " plus ", "=" to " equals ",
    )
}
