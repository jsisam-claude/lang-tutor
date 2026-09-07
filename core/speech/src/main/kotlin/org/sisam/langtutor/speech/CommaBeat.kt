package org.sisam.langtutor.speech

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Gives a comma on a short line the beat it is owed.
 *
 * Kokoro's only pause is a punctuation token, and how long that pause lasts
 * depends on the whole utterance: the duration predictor conditions on the
 * sequence and the style row is indexed by token count. Measured on real
 * bank lines (longest quiet within a quarter second of the comma, at −25 dB
 * below peak): a comma buys ~196 ms on an 8–9 word line, ~149 ms on 6–7,
 * and on the lines that matter most it buys nothing: "One cake, please."
 * 55 ms with the comma, 55 without; "This ship, these sheep." 55 versus
 * 50. Eleven of the twisters' lines carry a comma, and there the comma IS
 * the lesson — it separates the two halves of the minimal pair.
 *
 * No token in the vocabulary rescues it. Measured on thirteen short comma
 * lines: `…` 130 ms mean, `;` 129, `:` 132, `,…` 149 — and "One cake,
 * please." stays at 55–120 whichever is used. Nor can silence be spliced
 * into the render at a guessed position: the karaoke's proportional word
 * boundary was measured 150–350 ms off, and a search around it found a
 * different quiet gap — a stop closure inside a word — on seven of
 * thirteen lines.
 *
 * So a short line is cut at its marks and each piece is rendered whole, and
 * the pieces are joined with a beat. The quiet Kokoro already leaves at the
 * end of one render and the start of the next counts toward it, so the
 * total is what was asked for, and no sample of speech is ever touched.
 * What this costs is the contour across the comma, which on these lines
 * the model was not delivering anyway. Long lines are left alone: there the
 * model paces the comma itself.
 */
object CommaBeat {

    /** A stretch of the line rendered on its own: [start, end) in the text. */
    data class Piece(val start: Int, val end: Int)

    /** One rendered piece, ready to join. [charOffset] is where its text
     *  begins in the line the timing is reported against. */
    class Part(val audio: FloatArray, val timing: List<KaraokeTiming.Word>, val charOffset: Int)

    /** Marks that owe a beat inside a line. A full stop ends a chunk and is
     *  the chunker's business. */
    private val PAUSE_MARKS = setOf(',', '…', '—', ';')

    /** A sentence longer than this the model paces itself (measured). */
    const val MAX_WORDS = 6

    /** Total quiet at the seam, at 1.0× speed: a shade under what the model
     *  gives an eight-word line on its own. */
    const val TARGET_MS = 180

    /** Below this, relative to the render's peak, the edge is quiet. */
    private const val EDGE_QUIET_DB = -40.0
    private const val WINDOW_MS = 10

    /** Quote marks that may trail a word after its comma. */
    private val TRAILING = setOf('"', '”', '\'', '’', ')')

    /**
     * Where [text] is cut. One piece — the whole line — when it is long, or
     * carries no mark, so the caller's ordinary path is the common path.
     */
    fun pieces(text: String): List<Piece> {
        val whole = listOf(Piece(0, text.length))
        // Per SENTENCE, not per line: the engine groups two sentences for
        // their shared contour, and "Thank you, Grandma." is still a short
        // line when the next sentence follows it. The cut after "you," is
        // taken; the sentence after it rides along in the second piece.
        val cuts = SentenceChunker.split(text).flatMap { chunk ->
            val spans = KaraokeTiming.wordSpans(chunk.text)
            if (spans.size > MAX_WORDS || spans.size < 2) return@flatMap emptyList()
            spans.dropLast(1)
                .filter { (s, e) -> endsWithMark(chunk.text.substring(s, e)) }
                .map { (s, e) -> (s + chunk.start) to (e + chunk.start) }
        }
        if (cuts.isEmpty()) return whole
        val out = ArrayList<Piece>(cuts.size + 1)
        var start = 0
        fun add(end: Int) {
            // A piece with nothing to voice — a dash standing alone between
            // two words — is not a piece: rendered by itself, Kokoro says a
            // short "uh" for it. It stays attached to the words before it
            // (or, at the very start of the line, to the words after).
            val voiced = text.substring(start, end).any { it.isLetterOrDigit() }
            when {
                voiced -> out.add(Piece(start, end))
                out.isNotEmpty() -> out[out.size - 1] = Piece(out.last().start, end)
                else -> return
            }
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        for ((_, e) in cuts) add(e)
        if (start < text.length) add(text.length)
        return if (out.size >= 2) out else whole
    }

    /** The word ends in a mark, as typed or as the normaliser would read it
     *  ("..." is an ellipsis, "--" a dash). */
    private fun endsWithMark(word: String): Boolean {
        val w = word.trimEnd { it in TRAILING }
        return w.lastOrNull() in PAUSE_MARKS || w.endsWith("...") || w.endsWith("--")
    }

    /**
     * The pieces' renders, one after another, with a beat at each seam and
     * every word's karaoke start where it now falls.
     *
     * Kokoro pads every render with ~400 ms of quiet at the front and
     * ~500 ms at the back (measured, −40 dB below peak). Left in, two
     * renders butted together would sit a full second apart; so the seam is
     * cut down to the target, and only ever from that padding — the samples
     * farthest from the speech on either side. When the padding is shorter
     * than the beat, silence is added instead.
     */
    fun join(parts: List<Part>, sampleRate: Int, speed: Float = 1f): Pair<FloatArray, List<KaraokeTiming.Word>> {
        require(parts.isNotEmpty())
        if (parts.size == 1) {
            // One survivor of a cut still speaks from its own offset.
            val only = parts[0]
            return only.audio to only.timing.map {
                it.copy(charStart = it.charStart + only.charOffset, charEnd = it.charEnd + only.charOffset)
            }
        }
        val target = (TARGET_MS * sampleRate / 1000 / speed.coerceAtLeast(0.25f)).toInt()
        val window = WINDOW_MS * sampleRate / 1000
        // Per seam: how much of the tail before it and the head after it to
        // drop, or how much silence to add. Decided first, so the output is
        // allocated once.
        val cutTail = IntArray(parts.size)
        val cutHead = IntArray(parts.size)
        val gap = IntArray(parts.size)
        for (i in 0 until parts.size - 1) {
            val tail = trailingQuiet(parts[i].audio, window)
            val head = leadingQuiet(parts[i + 1].audio, window)
            val excess = tail + head - target
            if (excess > 0) {
                // Keep each side's share of the beat, nearest the speech.
                val keepTail = if (tail + head == 0) 0 else target.toLong() * tail / (tail + head)
                cutTail[i] = tail - keepTail.toInt()
                cutHead[i + 1] = head - (target - keepTail.toInt())
            } else {
                gap[i] = -excess
            }
        }
        // A part that is quiet end to end (an empty render, a silent one)
        // is claimed from both sides; it can give up only what it has.
        for (i in parts.indices) {
            val over = cutHead[i] + cutTail[i] - parts[i].audio.size
            if (over > 0) cutTail[i] = (cutTail[i] - over).coerceAtLeast(0)
        }
        var total = 0
        for (i in parts.indices) total += parts[i].audio.size - cutTail[i] - cutHead[i] + gap[i]
        val out = FloatArray(total)
        val words = ArrayList<KaraokeTiming.Word>()
        var at = 0
        for ((i, part) in parts.withIndex()) {
            val from = cutHead[i]
            val length = part.audio.size - cutHead[i] - cutTail[i]
            System.arraycopy(part.audio, from, out, at, length)
            for (w in part.timing) {
                // A word whose estimated start fell in the padding that was
                // cut is pinned to what remains of its piece, never past it.
                val start = (w.startFrame - from).coerceIn(0, (length - 1).coerceAtLeast(0))
                words.add(
                    KaraokeTiming.Word(
                        charStart = w.charStart + part.charOffset,
                        charEnd = w.charEnd + part.charOffset,
                        startFrame = start + at,
                    ),
                )
            }
            at += length + gap[i] // the gap is zeros: FloatArray is born silent
        }
        return out to words
    }

    /** Samples of quiet at the end of [audio]. */
    private fun trailingQuiet(audio: FloatArray, window: Int): Int {
        val quiet = quietLevel(audio)
        var end = audio.size
        while (end - window >= 0 && rms(audio, end - window, window) <= quiet) end -= window
        return audio.size - end
    }

    /** Samples of quiet at the start of [audio]. */
    private fun leadingQuiet(audio: FloatArray, window: Int): Int {
        val quiet = quietLevel(audio)
        var start = 0
        while (start + window <= audio.size && rms(audio, start, window) <= quiet) start += window
        return start
    }

    private fun quietLevel(audio: FloatArray): Float {
        var peak = 0f
        for (v in audio) peak = maxOf(peak, abs(v))
        return peak * 10.0.pow(EDGE_QUIET_DB / 20).toFloat()
    }

    private fun rms(audio: FloatArray, start: Int, length: Int): Float {
        var sum = 0.0
        for (i in start until start + length) sum += audio[i].toDouble() * audio[i]
        return sqrt(sum / length).toFloat()
    }
}
