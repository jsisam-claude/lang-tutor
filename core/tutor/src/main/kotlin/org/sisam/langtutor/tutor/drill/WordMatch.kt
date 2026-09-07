package org.sisam.langtutor.tutor.drill

import org.sisam.langtutor.speech.KokoroTextNormalizer

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
 * Two words are the same word when they are SPELLED the same or SOUND the
 * same ([Pronunciation]). The recogniser writes "10 fingers" for "Ten
 * fingers." and "okay" for "OK", and a judge that read only spelling failed
 * a learner who had said both perfectly — measured at 9.3% of every attempt
 * on the bank's short items, where the allowance is zero and one spelling
 * choice is the whole verdict. Numbers are normalised on both sides first
 * ([KokoroTextNormalizer], the same spoken-form rules the voice uses), and
 * then a token matches a token when their stress-stripped phonemes are
 * identical. Identical: "ship" and "sheep" still differ, and so does every
 * other contrast the app teaches.
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

    /**
     * The target's tokens in their spoken form, one for one with [tokens] of
     * the raw text — a "3" becomes "three", but a token that would become
     * several ("$5" → "five dollars") stays as written, so the indexes
     * [missedWordIndexes] returns still name the words on the screen.
     */
    private fun spokenTarget(target: String): List<String> =
        tokens(target).map { t -> tokens(KokoroTextNormalizer.normalize(t)).singleOrNull() ?: t }

    /** The transcript in its spoken form: digits as words, diacritics folded. */
    private fun spokenTranscript(transcript: String): List<String> =
        tokens(KokoroTextNormalizer.normalize(transcript))

    /** How many target words are missing from the transcript (multiset). */
    fun missing(target: String, transcript: String, pronunciation: Pronunciation = Pronunciation.NONE): Int =
        missedWordIndexes(target, transcript, pronunciation).size

    /**
     * WHICH target words (by token index) the transcript is missing — the
     * post-attempt karaoke: said words stay plain, missed ones are marked.
     * Token index equals whitespace-word index for ordinary text; a caller
     * displaying by whitespace words should check the counts line up first
     * (a hyphenated word splits into two tokens).
     */
    fun missedWordIndexes(
        target: String,
        transcript: String,
        pronunciation: Pronunciation = Pronunciation.NONE,
    ): Set<Int> {
        val need = spokenTarget(target)
        val said = spokenTranscript(transcript)
        if (need.isEmpty()) return emptySet()
        // Keyed once per token, not once per comparison: the LCS below asks
        // about every pair, and a pronunciation is a dictionary walk.
        val needKey = Array(need.size) { pronunciation.key(need[it]) }
        val saidKey = Array(said.size) { pronunciation.key(said[it]) }
        fun same(i: Int, j: Int): Boolean =
            need[i] == said[j] || (needKey[i] != null && needKey[i] == saidKey[j])
        // One word written as two, or two as one: "pinecones" and "pine
        // cones", "I see" and "Icy", "good night" and "goodnight". The join
        // must be exact — the same letters or the same sounds end to end —
        // which is why it cannot be used to slip a word past the count.
        fun joins(whole: String, wholeKey: String?, a: String, aKey: String?, b: String, bKey: String?): Boolean =
            whole == a + b || (wholeKey != null && aKey != null && bKey != null && wholeKey == aKey + bKey)
        fun oneSaidAsTwo(i: Int, j: Int): Boolean = // need[i] == said[j - 1] + said[j]
            j >= 1 && joins(need[i], needKey[i], said[j - 1], saidKey[j - 1], said[j], saidKey[j])
        fun twoSaidAsOne(i: Int, j: Int): Boolean = // need[i - 1] + need[i] == said[j]
            i >= 1 && joins(said[j], saidKey[j], need[i - 1], needKey[i - 1], need[i], needKey[i])
        // IN ORDER, not as a bag of words. Counting a multiset made word
        // ORDER free: "You are playing with the blocks" scored as a flawless
        // repetition of "Are you playing with the blocks?" — the inversion is
        // the entire grammar point of that item, and the room called it
        // perfect and moved on. Longest common subsequence counts a word as
        // said only where it can be matched in sequence, so the one that moved
        // is the one that gets marked.
        // lcs[i][j]: target words counted as said using need[0, i) and said[0, j).
        val lcs = Array(need.size + 1) { IntArray(said.size + 1) }
        for (i in need.indices) {
            for (j in said.indices) {
                var best = maxOf(lcs[i][j + 1], lcs[i + 1][j])
                if (same(i, j)) best = maxOf(best, lcs[i][j] + 1)
                if (oneSaidAsTwo(i, j)) best = maxOf(best, lcs[i][j - 1] + 1)
                if (twoSaidAsOne(i, j)) best = maxOf(best, lcs[i - 1][j] + 2)
                lcs[i + 1][j + 1] = best
            }
        }
        val missed = mutableSetOf<Int>()
        var i = need.size
        var j = said.size
        while (i > 0) {
            when {
                j > 0 && twoSaidAsOne(i - 1, j - 1) && lcs[i][j] == lcs[i - 2][j - 1] + 2 -> {
                    i -= 2; j--
                }
                j > 0 && oneSaidAsTwo(i - 1, j - 1) && lcs[i][j] == lcs[i - 1][j - 2] + 1 -> {
                    i--; j -= 2
                }
                j > 0 && same(i - 1, j - 1) && lcs[i][j] == lcs[i - 1][j - 1] + 1 -> {
                    i--; j--
                }
                j > 0 && lcs[i][j] == lcs[i][j - 1] -> j--
                else -> { i--; missed.add(i) }
            }
        }
        return missed
    }

    fun matches(target: String, transcript: String, pronunciation: Pronunciation = Pronunciation.NONE): Boolean {
        val need = tokens(target)
        if (need.isEmpty()) return false
        return missing(target, transcript, pronunciation) <= allowedMisses(need.size)
    }

    /**
     * Every target word said, in order, with nothing missing.
     *
     * For decisions where a false positive costs more than a late answer —
     * the early close, which ends the turn while the finger is still down. A
     * forgiving match there cuts the child off mid-sentence and praises a
     * line they had not finished saying.
     */
    fun matchesExactly(target: String, transcript: String, pronunciation: Pronunciation = Pronunciation.NONE): Boolean {
        val need = tokens(target)
        return need.isNotEmpty() && missing(target, transcript, pronunciation) == 0
    }

    /** 1–3 words: perfect. 4–7: one miss. 8+: two. */
    fun allowedMisses(words: Int): Int = words / 4
}
