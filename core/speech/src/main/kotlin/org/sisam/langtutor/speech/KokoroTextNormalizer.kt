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
        // Three dots and two hyphens are how an ellipsis and a dash get typed;
        // the voice has a pause token for each, and the model that writes the
        // chat room's lines uses both. Only a SPACED dash is a pause: an en
        // dash inside "well–done" or "1–2" is a hyphen and stays one.
        t = ELLIPSIS.replace(t, "…")
        t = SPACED_DASH.replace(t, "—")
        t = t.replace('–', '-')
        t = DOLLARS.replace(t) { m ->
            val dollars = cardinal(m.groupValues[1].replace(",", "").toLong())
            val cents = m.groupValues[2]
            if (cents.isEmpty()) "$dollars dollars"
            else "$dollars dollars and ${cardinal(cents.padEnd(2, '0').toLong())} cents"
        }
        t = PERCENT.replace(t) { m -> "${spokenNumber(m.groupValues[1])} percent" }
        // "1st" and "3rd" the way a recogniser writes them, before the
        // letter-digit split below would turn them into "one st".
        t = ORDINAL.replace(t) { m -> ordinal(m.groupValues[1]) ?: m.value }
        t = NUMBER.replace(t) { m -> spokenNumber(m.value) }
        // "covid19" style boundaries become separate tokens. After the
        // numbers, so an ordinal's suffix is never split off first.
        t = LETTER_DIGIT_BOUNDARY.replace(t, " ")
        for ((symbol, word) in SYMBOL_WORDS) t = t.replace(symbol, word)
        return t.replace(WHITESPACE, " ").trim()
    }

    /** "3.14" → "three point one four"; plain integers → cardinal words. A
     *  run of digits too long for a number is read digit by digit rather
     *  than thrown at — the judge runs this on whatever the recogniser
     *  wrote, on the early-close path, where a throw is a crash. */
    private fun spokenNumber(raw: String): String {
        val s = raw.replace(",", "")
        val dot = s.indexOf('.')
        if (dot < 0) return s.toLongOrNull()?.let { cardinal(it) } ?: digits(s)
        val whole = s.take(dot).ifEmpty { "0" }.let { w -> w.toLongOrNull()?.let { cardinal(it) } ?: digits(w) }
        val fraction = digits(s.substring(dot + 1))
        return "$whole point $fraction"
    }

    private fun digits(s: String): String = s.map { ONES[it - '0'] }.joinToString(" ")

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

    /** "1st" → "first", "22nd" → "twenty second", "100th" → "one hundredth";
     *  null for a number this cannot name, which is then left as written. */
    private fun ordinal(digits: String): String? {
        val n = digits.toLongOrNull() ?: return null
        if (n <= 0) return null
        val last = (n % 100).toInt()
        val head = n - last
        val headWords = if (head > 0) cardinal(head) + " " else ""
        return when {
            last == 0 -> {
                val words = cardinal(n)
                val scale = words.substringAfterLast(' ')
                words.dropLast(scale.length) + (SCALE_ORDINALS[scale] ?: return null)
            }
            last < 20 -> headWords + ORDINAL_ONES[last]
            last % 10 == 0 -> headWords + ORDINAL_TENS[last / 10]
            else -> headWords + TENS[last / 10] + " " + ORDINAL_ONES[last % 10]
        }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val ELLIPSIS = Regex("\\.{3,}")
    private val SPACED_DASH = Regex("(?<=\\s|^)(?:--|–|—)(?=\\s|$)")
    private val LETTER_DIGIT_BOUNDARY = Regex("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])")
    private val DOLLARS = Regex("\\$\\s?(\\d+(?:,\\d{3})*)(?:\\.(\\d{1,2}))?")
    private val PERCENT = Regex("(\\d+(?:\\.\\d+)?)\\s?%")
    private val NUMBER = Regex("\\d+(?:,\\d{3})*(?:\\.\\d+)?")
    private val ORDINAL = Regex("\\b(\\d+)(?:st|nd|rd|th)\\b")
    private val WHITESPACE = Regex("\\s+")

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )
    private val ORDINAL_ONES = arrayOf(
        "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth",
    )
    private val ORDINAL_TENS = arrayOf(
        "", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth",
    )
    private val SCALE_ORDINALS = mapOf(
        "hundred" to "hundredth", "thousand" to "thousandth", "million" to "millionth", "billion" to "billionth",
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
