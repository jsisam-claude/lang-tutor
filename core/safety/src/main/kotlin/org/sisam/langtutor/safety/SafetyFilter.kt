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

    private val blockedRegex = Regex(
        blockedTerms.joinToString("|", prefix = "\\b(", postfix = ")\\b") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    override fun check(text: String): SafetyVerdict = when {
        text.isBlank() -> SafetyVerdict(false, "empty")
        text.length > maxChars -> SafetyVerdict(false, "too-long")
        blockedRegex.containsMatchIn(text) -> SafetyVerdict(false, "blocked-term")
        URL_REGEX.containsMatchIn(text) -> SafetyVerdict(false, "contains-url")
        META_AI_REGEX.containsMatchIn(text) -> SafetyVerdict(false, "meta-ai-talk")
        else -> SafetyVerdict(true)
    }

    companion object {
        const val MAX_REPLY_CHARS = 400

        // Child-tutor output should never need any of these. Word-boundary
        // matched, so "skill" does not trip on "kill".
        val DEFAULT_BLOCKLIST = setOf(
            "kill", "killed", "die", "died", "dead", "death", "gun", "guns",
            "knife", "blood", "bomb", "war", "drug", "drugs", "beer", "wine",
            "vodka", "cigarette", "smoke", "sexy", "naked", "kiss me",
            "stupid", "idiot", "dumb", "hate you", "shut up", "ugly",
            "your address", "phone number", "password", "credit card",
            "secret from your parents",
        )

        private val URL_REGEX = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
        private val META_AI_REGEX = Regex(
            "as an ai|language model|i was trained|my training data",
            RegexOption.IGNORE_CASE,
        )
    }
}
