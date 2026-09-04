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
        val need = tokens(target)
        val said = tokens(transcript)
        if (need.isEmpty()) return emptySet()
        // IN ORDER, not as a bag of words. Counting a multiset made word
        // ORDER free: "You are playing with the blocks" scored as a flawless
        // repetition of "Are you playing with the blocks?" — the inversion is
        // the entire grammar point of that item, and the room called it
        // perfect and moved on. Longest common subsequence counts a word as
        // said only where it can be matched in sequence, so the one that moved
        // is the one that gets marked.
        val lcs = Array(need.size + 1) { IntArray(said.size + 1) }
        for (i in need.indices) {
            for (j in said.indices) {
                lcs[i + 1][j + 1] = if (need[i] == said[j]) {
                    lcs[i][j] + 1
                } else {
                    maxOf(lcs[i][j + 1], lcs[i + 1][j])
                }
            }
        }
        val missed = mutableSetOf<Int>()
        var i = need.size
        var j = said.size
        while (i > 0) {
            when {
                j > 0 && need[i - 1] == said[j - 1] && lcs[i][j] == lcs[i - 1][j - 1] + 1 -> {
                    i--; j--
                }
                j > 0 && lcs[i][j] == lcs[i][j - 1] -> j--
                else -> { i--; missed.add(i) }
            }
        }
        return missed
    }

    fun matches(target: String, transcript: String): Boolean {
        val need = tokens(target)
        if (need.isEmpty()) return false
        return missing(target, transcript) <= allowedMisses(need.size)
    }

    /**
     * Every target word said, in order, with nothing missing.
     *
     * For decisions where a false positive costs more than a late answer —
     * the early close, which ends the turn while the finger is still down. A
     * forgiving match there cuts the child off mid-sentence and praises a
     * line they had not finished saying.
     */
    fun matchesExactly(target: String, transcript: String): Boolean {
        val need = tokens(target)
        return need.isNotEmpty() && missing(target, transcript) == 0
    }

    /** 1–3 words: perfect. 4–7: one miss. 8+: two. */
    fun allowedMisses(words: Int): Int = words / 4
}
