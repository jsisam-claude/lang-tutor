package org.sisam.langtutor.tutor.drill

import org.sisam.langtutor.content.AlignCue

/**
 * Turns a phrasebank alignment (docs/phrasebank.md) into the meaning row's
 * synchronized highlight: while karaoke bolds English word N, the Hebrew
 * words that MEAN it light up inside the naturally-ordered translation line.
 *
 * This is the whole point of the cues being spans and not timestamps — the
 * Hebrew sentence keeps its own word order and nothing is rearranged; only
 * emphasis moves. Malformed cues (wrong arity, reversed or negative spans)
 * are skipped rather than guessed at: a highlight that drifted one word is
 * worse than none, because the reader has no way to tell.
 */
object AlignHighlight {

    /** Hebrew word indexes to light while English word [enWordIndex] sounds. */
    fun hebrewWordsFor(enWordIndex: Int, cues: List<AlignCue>): Set<Int> {
        val out = mutableSetOf<Int>()
        for (cue in cues) {
            if (cue.en.size != 2 || cue.he.size != 2) continue
            val (e0, e1) = cue.en
            val (h0, h1) = cue.he
            if (e0 < 0 || h0 < 0 || e1 < e0 || h1 < h0) continue
            if (enWordIndex in e0..e1) out += h0..h1
        }
        return out
    }
}
