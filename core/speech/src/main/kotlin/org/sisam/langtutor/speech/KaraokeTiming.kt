package org.sisam.langtutor.speech

/**
 * When does each word of a synthesized line start sounding?
 *
 * Kokoro gives back one waveform per group with no per-word timestamps, so
 * the word boundaries are ESTIMATED: each word's share of the audio is
 * proportional to its share of the group's phonemes (the same G2P that fed
 * the synthesizer, applied per word). That is not exact — pauses at commas
 * and sentence ends stretch their neighbours — but it is derived from the
 * actual voice and speed, which is why the phrasebank deliberately carries
 * no authored timestamps (docs/phrasebank.md): structure is authored, time
 * belongs to the synthesizer.
 *
 * The consumer maps a live playback-head frame count to the last word whose
 * [Word.startFrame] has been reached. The first word always starts at frame
 * 0, so that lookup can never come up empty.
 *
 * Pure JVM; the phoneme counter is injected so the estimate is testable.
 */
object KaraokeTiming {

    /** One word's place in the text and the frame its audio begins. */
    data class Word(val charStart: Int, val charEnd: Int, val startFrame: Int)

    /**
     * Split [text] on whitespace (offsets preserved, punctuation attached —
     * the same shape the gloss row renders) and prorate [totalFrames] over
     * the words by phoneme count. A word the G2P cannot voice still gets a
     * floor weight of one, so timing never divides by zero and unvoiced
     * tokens pass quickly instead of vanishing.
     */
    fun of(text: String, phonemeCount: (String) -> Int, totalFrames: Int): List<Word> {
        if (totalFrames <= 0) return emptyList()
        val spans = wordSpans(text)
        if (spans.isEmpty()) return emptyList()
        val weights = spans.map { (start, end) ->
            runCatching { phonemeCount(text.substring(start, end)) }.getOrDefault(0).coerceAtLeast(1)
        }
        val totalWeight = weights.sum()
        var acc = 0
        return spans.mapIndexed { i, (start, end) ->
            val startFrame = if (i == 0) 0 else acc * totalFrames / totalWeight
            acc += weights[i]
            Word(start, end, startFrame)
        }
    }

    /** (startInclusive, endExclusive) character spans of whitespace-words. */
    fun wordSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            spans.add(start to i)
        }
        return spans
    }
}
