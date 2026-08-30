package org.sisam.langtutor.tutor.drill

/**
 * Did the learner say the sentence? Transcript-based, deliberately forgiving.
 *
 * The judge compares WORDS, not audio: ASR has already turned the attempt into
 * text, and the question is whether the target's words are in it. Extra words
 * around the target are free ("um, I see a red ball, yes!" passes), because
 * punishing enthusiasm teaches a learner to say less. What is NOT free is
 * missing words — with an allowance that grows with sentence length, since a
 * five-word sentence with four right words is a success for a beginner while
 * a one-word item said wrong is just wrong.
 *
 * Pronunciation quality is deliberately NOT judged here — the GOP scorer does
 * that separately and its marks are shown, not gated on. Gating progress on
 * per-sound scores calibrated against synthesized speech would fail children
 * for having children's voices.
 */
object WordMatch {

    /** Lowercased words; punctuation split, apostrophes kept ("don't"). */
    fun tokens(text: String): List<String> = text
        .lowercase()
        .map { if (it.isLetterOrDigit() || it == '\'' || it == '’') it else ' ' }
        .joinToString("")
        .replace('’', '\'')
        .split(' ')
        .filter { it.isNotEmpty() }

    /** How many target words are missing from the transcript (multiset). */
    fun missing(target: String, transcript: String): Int =
        missedWordIndexes(target, transcript).size

    /**
     * WHICH target words (by token index) the transcript is missing — the
     * post-attempt karaoke: said words stay plain, missed ones are marked.
     * Token index equals whitespace-word index for ordinary text; a caller
     * displaying by whitespace words should check the counts line up first
     * (a hyphenated word splits into two tokens).
     */
    fun missedWordIndexes(target: String, transcript: String): Set<Int> {
        val said = tokens(transcript).groupingBy { it }.eachCount().toMutableMap()
        val missed = mutableSetOf<Int>()
        tokens(target).forEachIndexed { index, word ->
            val have = said[word] ?: 0
            if (have > 0) said[word] = have - 1 else missed.add(index)
        }
        return missed
    }

    fun matches(target: String, transcript: String): Boolean {
        val need = tokens(target)
        if (need.isEmpty()) return false
        return missing(target, transcript) <= allowedMisses(need.size)
    }

    /** 1–3 words: perfect. 4–7: one miss. 8+: two. */
    fun allowedMisses(words: Int): Int = words / 4
}
