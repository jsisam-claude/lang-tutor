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
 * ([KokoroTextNormalizer], the same spoken-form rules the voice uses),
 * contractions are opened ("don't" is "do not" whichever side wrote it),
 * and then a token matches a token when their stress-stripped phonemes are
 * identical. Identical: "ship" and "sheep" still differ, and so does every
 * other contrast the app teaches.
 *
 * The allowance forgives a word LEFT OUT and nothing else. Three things it
 * used to pay for, each counted against the bank (docs/loop-accuracy.md):
 * a word said WRONG — a different word in the target word's place, which on
 * every 4+ word line (93% of the bank) cost exactly the one miss the
 * allowance covers, so the room whose purpose is one contrast could not
 * fail the one word that carried it; a NEGATION dropped, which passed 155
 * of 156 negative lines with their meaning reversed; and a word MOVED —
 * present but out of order — which passed 174 of 175 questions said as
 * statements, though the inversion was the entire grammar point. A
 * substitution, a dropped negation and a moved word now fail the line
 * outright. What this trades: a mid-sentence word the recogniser mishears
 * as another word used to be forgiven and is not now — measured, and the
 * number is in the doc.
 *
 * Pronunciation quality is deliberately NOT judged here — the GOP scorer does
 * that separately and its marks are shown, not gated on. Gating progress on
 * per-sound scores calibrated against synthesized speech would fail children
 * for having children's voices.
 */
object WordMatch {

    /**
     * What one attempt got wrong, by kind. [missed] indexes the target's own
     * words (for the karaoke); the counts are over the opened-contraction
     * tokens the judge actually compared.
     */
    data class Judgement(
        /** Words of the target as written (whitespace words, minus punctuation). */
        val words: Int,
        /** Target word indexes not said, of any kind. */
        val missed: Set<Int>,
        /** Left out, with nothing in their place — the forgivable kind. */
        val omitted: Int,
        /** A different word where the target word should be. */
        val substituted: Int,
        /** Said, but somewhere else in the line. */
        val moved: Int,
        /** A "not", "no", "never" or the like that was not said. */
        val negationDropped: Int,
        /** One said that the line does not have: "I don't like peas" for
         *  "I like peas" is the line reversed, however free extras are. */
        val negationAdded: Int = 0,
    ) {
        val unforgivable: Int get() = substituted + moved + negationDropped + negationAdded
    }

    /** Lowercased words; punctuation split, apostrophes kept ("don't"). */
    fun tokens(text: String): List<String> = text
        .lowercase()
        .map { if (it.isLetterOrDigit() || it == '\'' || it == '’') it else ' ' }
        .joinToString("")
        .replace('’', '\'')
        .split(' ')
        .filter { it.isNotEmpty() }

    /**
     * The target's tokens in their spoken form, each tagged with the index
     * of the WRITTEN word — the whitespace word — it came from: "3" becomes
     * "three" at the same index, "don't" becomes "do" and "not" at the same
     * index, "1,000" becomes "one" and "thousand" at the same index. So the
     * indexes [Judgement.missed] carries name the words on the screen.
     */
    private fun spokenTarget(target: String): List<Pair<String, Int>> =
        target.split(WHITESPACE).filter { it.isNotBlank() }.flatMapIndexed { index, word ->
            val opened = tokens(spoken(word)).flatMap { CONTRACTIONS[it] ?: listOf(it) }.ifEmpty { tokens(word) }
            opened.map { it to index }
        }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The transcript in its spoken form: digits as words, diacritics
     * folded, contractions opened — an "X's" in the light of the target,
     * because on its own it is ambiguous. "It's been raining" against a
     * target that says "It has been raining" opens to "it has"; "in it's
     * shell" against a target that says "its" stays closed, since the two
     * are one sound and the key will match them; otherwise it is "X is".
     */
    private fun spokenTranscript(transcript: String, need: List<String>, needKey: Array<String?>, pronunciation: Pronunciation): List<String> {
        val pairs = need.zipWithNext().toSet()
        return tokens(spoken(transcript)).flatMap { t ->
            val head = APOSTROPHE_S[t]
            if (head == null) return@flatMap CONTRACTIONS[t] ?: listOf(t)
            when {
                (head to "has") in pairs -> listOf(head, "has")
                need.any { it == t } -> listOf(t)
                pronunciation.key(t)?.let { k -> needKey.any { it == k } } == true -> listOf(t)
                else -> listOf(head, "is")
            }
        }
    }

    /** The normaliser never throws on what a recogniser writes; if it
     *  somehow did, the text as written is the fallback, not a crash on
     *  the early-close path. */
    private fun spoken(text: String): String =
        runCatching { KokoroTextNormalizer.normalize(text) }.getOrDefault(text)

    /** How many target words are missing from the transcript (multiset). */
    fun missing(target: String, transcript: String, pronunciation: Pronunciation = Pronunciation.NONE): Int =
        judge(target, transcript, pronunciation).missed.size

    /**
     * WHICH target words (by whitespace-word index) the transcript is
     * missing — the post-attempt karaoke: said words stay plain, missed ones
     * are marked. Always the whitespace-word index, whatever the word is
     * made of: a hyphenated word or a numeral is one word on the screen and
     * one index here.
     */
    fun missedWordIndexes(
        target: String,
        transcript: String,
        pronunciation: Pronunciation = Pronunciation.NONE,
    ): Set<Int> = judge(target, transcript, pronunciation).missed

    fun judge(
        target: String,
        transcript: String,
        pronunciation: Pronunciation = Pronunciation.NONE,
    ): Judgement {
        val written = tokens(target).size
        val tagged = spokenTarget(target)
        val need = tagged.map { it.first }
        if (need.isEmpty()) return Judgement(0, emptySet(), 0, 0, 0, 0)
        // Keyed once per token, not once per comparison: the LCS below asks
        // about every pair, and a pronunciation is a dictionary walk.
        val needKey = Array(need.size) { pronunciation.key(need[it]) }
        val said = spokenTranscript(transcript, need, needKey, pronunciation)
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
        // is the one that gets marked — and, below, the one that fails it.
        //
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
        // Backtrace, keeping the alignment: which target token met which
        // transcript token, so that what sits in the GAPS can be read.
        val matchedNeed = BooleanArray(need.size)
        val matchedSaid = BooleanArray(said.size)
        val pairs = ArrayList<Pair<Int, Int>>() // (need index, said index), last first
        var i = need.size
        var j = said.size
        while (i > 0) {
            when {
                j > 0 && twoSaidAsOne(i - 1, j - 1) && lcs[i][j] == lcs[i - 2][j - 1] + 2 -> {
                    matchedNeed[i - 1] = true; matchedNeed[i - 2] = true; matchedSaid[j - 1] = true
                    pairs.add((i - 2) to (j - 1))
                    i -= 2; j--
                }
                j > 0 && oneSaidAsTwo(i - 1, j - 1) && lcs[i][j] == lcs[i - 1][j - 2] + 1 -> {
                    matchedNeed[i - 1] = true; matchedSaid[j - 1] = true; matchedSaid[j - 2] = true
                    pairs.add((i - 1) to (j - 2))
                    i--; j -= 2
                }
                j > 0 && same(i - 1, j - 1) && lcs[i][j] == lcs[i - 1][j - 1] + 1 -> {
                    matchedNeed[i - 1] = true; matchedSaid[j - 1] = true
                    pairs.add((i - 1) to (j - 1))
                    i--; j--
                }
                // On a tie, leave the LATER target word unsaid rather than
                // skip a transcript word: "bye bye" heard as "bye bike"
                // then pairs the unsaid "bye" with "bike" in one gap, where
                // it reads as the substitution it is, instead of stranding
                // the first "bye" in a gap of its own as an omission.
                lcs[i][j] == lcs[i - 1][j] -> i--
                else -> j--
            }
        }
        pairs.reverse()
        // Sort what was not said, and what was said extra, by kind.
        val missedTokens = need.indices.filter { !matchedNeed[it] }
        fun alike(a: Int, b: Int): Boolean = said[a] == said[b] || (saidKey[a] != null && saidKey[a] == saidKey[b])
        // A filler, or a stutter (a word said twice in a row), is not a
        // word said INSTEAD of the target's and not a word MOVED: "I see a
        // um ball" left "red" out, and "the the cat and dog" left one "the"
        // out. Both stay the forgivable kind.
        val extras = said.indices.filter { s ->
            !matchedSaid[s] && said[s] !in FILLERS &&
                !(s > 0 && alike(s, s - 1)) && !(s + 1 < said.size && alike(s, s + 1))
        }
        // An added negation counts only INSIDE the matched line: "no, I like
        // peas" and the restart "I don't... I don't like peas" put theirs
        // before the line begins, and a preface is an extra like any other.
        // Inside it, a negation said twice ("I never never eat fish") is one
        // negation added, not a stutter that cancels itself. On an item of
        // one or two words there is no inside to speak of, so "no peas" for
        // "Peas." counts wherever it is; at three words, "no, I like peas"
        // is a preface again.
        val firstSaid = pairs.firstOrNull()?.second ?: 0
        val lastSaid = pairs.lastOrNull()?.second ?: -1
        val negationAdded = said.indices.count { s ->
            !matchedSaid[s] && said[s] in NEGATIONS &&
                (written <= 2 || s in (firstSaid + 1) until lastSaid) &&
                !(s > 0 && !matchedSaid[s - 1] && alike(s, s - 1)) &&
                // "no no thank you" for "No, thank you": a repeat of the
                // line's own word, beside it, is a stutter of it.
                !(s + 1 < said.size && matchedSaid[s + 1] && alike(s, s + 1)) &&
                !(s > 0 && matchedSaid[s - 1] && alike(s, s - 1))
        }
        // Substitutions: in the gap between two consecutive matches (or an
        // edge), an unsaid target token facing an unmatched transcript token.
        var prevI = -1
        var prevJ = -1
        val substitutedAt = HashSet<Int>()
        for ((pi, pj) in pairs + listOf(need.size to said.size)) {
            val gapNeed = missedTokens.filter { it in (prevI + 1) until pi }
            val gapSaid = extras.count { it in (prevJ + 1) until pj && said[it] !in NEGATIONS }
            for (k in gapNeed.take(gapSaid)) substitutedAt.add(k)
            prevI = pi; prevJ = pj
        }
        var substituted = 0
        var moved = 0
        var negation = 0
        var omitted = 0
        for (k in missedTokens) {
            val movedHere = extras.any { s -> need[k] == said[s] || (needKey[k] != null && needKey[k] == saidKey[s]) }
            when {
                need[k] in NEGATIONS -> negation++
                movedHere -> moved++
                k in substitutedAt -> substituted++
                else -> omitted++
            }
        }
        val missed = missedTokens.map { tagged[it].second }.toSet()
        return Judgement(written, missed, omitted, substituted, moved, negation, negationAdded)
    }

    fun matches(target: String, transcript: String, pronunciation: Pronunciation = Pronunciation.NONE): Boolean {
        val j = judge(target, transcript, pronunciation)
        return j.words > 0 && j.unforgivable == 0 && j.omitted <= allowedMisses(j.words)
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
        val j = judge(target, transcript, pronunciation)
        return j.words > 0 && j.missed.isEmpty() && j.negationAdded == 0
    }

    /** 1–3 words: perfect. 4–7: one miss. 8+: two. */
    fun allowedMisses(words: Int): Int = words / 4

    /** A word whose absence — or presence — reverses the line. */
    private val NEGATIONS = setOf(
        "not", "no", "never", "nothing", "nobody", "none", "nowhere", "neither", "nor", "cannot",
    )

    /** Sounds a recogniser writes down that are not words said instead of
     *  anything. Only the pure ones: "like" and "well" are also words. */
    private val FILLERS = setOf("um", "uh", "er", "erm", "hmm", "hm", "mm", "mhm", "ah", "oh", "eh", "huh")

    /**
     * Opened on both sides before comparing, so "don't" and "do not" are the
     * same words whichever the bank wrote and whichever the recogniser chose.
     * Not "'d", which is "would" or "had" and a guess would manufacture a
     * miss.
     */
    private val CONTRACTIONS: Map<String, List<String>> = mapOf(
        "don't" to listOf("do", "not"), "doesn't" to listOf("does", "not"), "didn't" to listOf("did", "not"),
        "can't" to listOf("can", "not"), "cannot" to listOf("can", "not"), "couldn't" to listOf("could", "not"),
        "won't" to listOf("will", "not"), "wouldn't" to listOf("would", "not"), "shouldn't" to listOf("should", "not"),
        "isn't" to listOf("is", "not"), "aren't" to listOf("are", "not"), "wasn't" to listOf("was", "not"),
        "weren't" to listOf("were", "not"), "haven't" to listOf("have", "not"), "hasn't" to listOf("has", "not"),
        "hadn't" to listOf("had", "not"), "mustn't" to listOf("must", "not"),
        "i'm" to listOf("i", "am"), "you're" to listOf("you", "are"), "we're" to listOf("we", "are"),
        "they're" to listOf("they", "are"), "i've" to listOf("i", "have"), "you've" to listOf("you", "have"),
        "we've" to listOf("we", "have"), "they've" to listOf("they", "have"),
        "i'll" to listOf("i", "will"), "you'll" to listOf("you", "will"), "we'll" to listOf("we", "will"),
        "they'll" to listOf("they", "will"), "he'll" to listOf("he", "will"), "she'll" to listOf("she", "will"),
        "it'll" to listOf("it", "will"), "let's" to listOf("let", "us"),
        // The written target's "'s" is "is": "It's a ball", "What's up?".
        // The transcript's is opened in [spokenTranscript], where the
        // target says which it is.
        "it's" to listOf("it", "is"), "that's" to listOf("that", "is"), "what's" to listOf("what", "is"),
        "there's" to listOf("there", "is"), "here's" to listOf("here", "is"), "he's" to listOf("he", "is"),
        "she's" to listOf("she", "is"), "who's" to listOf("who", "is"), "where's" to listOf("where", "is"),
        "how's" to listOf("how", "is"), "when's" to listOf("when", "is"),
        // Without the apostrophe, as a recogniser may write them. Only the
        // forms that are not also words: "its", "ill", "well", "were" stay.
        "dont" to listOf("do", "not"), "doesnt" to listOf("does", "not"), "didnt" to listOf("did", "not"),
        "cant" to listOf("can", "not"), "couldnt" to listOf("could", "not"), "wont" to listOf("will", "not"),
        "wouldnt" to listOf("would", "not"), "shouldnt" to listOf("should", "not"), "isnt" to listOf("is", "not"),
        "arent" to listOf("are", "not"), "wasnt" to listOf("was", "not"), "werent" to listOf("were", "not"),
        "havent" to listOf("have", "not"), "hasnt" to listOf("has", "not"), "hadnt" to listOf("had", "not"),
        "im" to listOf("i", "am"), "youre" to listOf("you", "are"), "theyre" to listOf("they", "are"),
        "ive" to listOf("i", "have"), "youve" to listOf("you", "have"), "weve" to listOf("we", "have"),
        "theyve" to listOf("they", "have"), "lets" to listOf("let", "us"),
    )

    /** "X's" on the TRANSCRIPT side, resolved against the target. */
    private val APOSTROPHE_S: Map<String, String> = listOf(
        "it", "that", "what", "there", "here", "he", "she", "who", "where", "how", "when",
    ).associateBy { "$it's" }
}
