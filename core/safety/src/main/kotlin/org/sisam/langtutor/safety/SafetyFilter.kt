package org.sisam.langtutor.safety

data class SafetyVerdict(val allowed: Boolean, val reason: String? = null)

/**
 * Runs on EVERY generated reply before it is shown or spoken (see
 * docs/architecture.md safety layers). Play's GenAI-for-kids policy requires
 * output filtering regardless of where inference runs.
 */
interface SafetyFilter {
    fun check(text: String): SafetyVerdict
}

/**
 * P1 filter: conservative word-boundary blocklist + structural checks. P3 adds
 * a small on-device classifier alongside; this deterministic layer never goes
 * away. False positives are acceptable here — the orchestrator swaps a blocked
 * reply for a safe scripted line, so the cost of over-blocking is mild.
 */
class BlocklistSafetyFilter(
    blockedTerms: Set<String> = DEFAULT_BLOCKLIST,
    private val maxChars: Int = MAX_REPLY_CHARS,
) : SafetyFilter {

    // (?U) makes \b and \w Unicode-aware. Without it, Java's word boundary
    // only knows [A-Za-z0-9_], so a HEBREW term compiled into the pattern can
    // never match at all — the tutor speaks Hebrew by design, and its only
    // output guard was silently English-only. Verified: "אתה טיפש" sailed
    // through the old pattern.
    // Known limit: Hebrew clitic prefixes (ו/ה/ב/ל/מ/ש) attach with no space,
    // so an inflected "והטיפש" is one \w-word and \b won't find טיפש inside
    // it. Dropping \b would fix that but re-introduce substring false
    // positives ("הרגשה" ⊃ הרג); word-boundary matching is the safer trade.
    private val blockedRegex = Regex(
        blockedTerms.joinToString("|", prefix = "(?U)\\b(", postfix = ")\\b") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    override fun check(text: String): SafetyVerdict {
        // Niqqud/cantillation (U+0591–U+05C7) are combining WORD characters
        // under (?U): a mark right after a term kills the trailing \b ("אתה
        // טיפשׁ" passed on the shin dot alone) and a mark inside breaks the
        // literal match ("דָם" vs "דם"). Pointed text is the normal children's
        // register — the app's own Hebrew pipeline exists to ADD niqqud — so
        // strip the marks before matching.
        val bare = text.replace(HEBREW_MARKS, "")
        return when {
            text.isBlank() -> SafetyVerdict(false, "empty")
            text.length > maxChars -> SafetyVerdict(false, REASON_TOO_LONG)
            blockedRegex.containsMatchIn(bare) -> SafetyVerdict(false, "blocked-term")
            URL_REGEX.containsMatchIn(text) -> SafetyVerdict(false, "contains-url")
            META_AI_REGEX.containsMatchIn(text) -> SafetyVerdict(false, "meta-ai-talk")
            else -> SafetyVerdict(true)
        }
    }

    companion object {
        const val MAX_REPLY_CHARS = 400

        /**
         * [SafetyVerdict.reason] for a reply rejected ONLY for length. The
         * orchestrator treats this differently from a content block: a clean
         * but endless reply is cut at a sentence boundary and keeps its audio,
         * instead of being replaced by the safety fallback.
         */
        const val REASON_TOO_LONG = "too-long"

        private val HEBREW_MARKS = Regex("[\u0591-\u05C7]")

        // Child-tutor output should never need any of these. Word-boundary
        // matched, so "skill" does not trip on "kill".
        val DEFAULT_BLOCKLIST = setOf(
            "kill", "killed", "die", "died", "dead", "death", "gun", "guns",
            "knife", "blood", "bomb", "war", "drug", "drugs", "beer", "wine",
            "vodka", "cigarette", "smoke", "sexy", "naked", "kiss me",
            "stupid", "idiot", "dumb", "hate you", "shut up", "ugly",
            "your address", "phone number", "password", "credit card",
            "secret from your parents",
            // Hebrew — the tutor emits Hebrew scaffolding by design, so the
            // guard must speak it too. Word-boundary matched under (?U).
            // "מת" (dead) is deliberately ABSENT: it collides with everyday
            // slang ("מת על זה" = loves it) and would over-block praise.
            "להרוג", "יהרוג", "הרג", "רצח", "מוות", "אקדח", "רובה", "סכין",
            "דם", "פצצה", "מלחמה", "סמים", "אלכוהול", "סיגריה", "עירום",
            // Both full (ktiv male) and defective (ktiv haser) spellings:
            // pointed text usually drops the yod (טִפֵּשׁ → טפש after marks
            // are stripped), so the unpointed form alone would miss it.
            "טיפש", "טיפשה", "טפש", "טפשה",
            "מטומטם", "דביל", "אידיוט", "מכוער", "מכוערת",
            "שונא אותך", "שונאת אותך", "שתוק", "שתקי",
            "סוד מההורים", "כתובת שלך", "סיסמה",
        )

        private val URL_REGEX = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
        private val META_AI_REGEX = Regex(
            "as an ai|language model|i was trained|my training data",
            RegexOption.IGNORE_CASE,
        )
    }
}
