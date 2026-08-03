package org.sisam.langtutor.speech

/**
 * Splits captured audio into pieces a fixed-window ASR export can actually see.
 *
 * Whisper's frontend pads or TRUNCATES to the export's window, so with a 10 s
 * graph everything a child says after the tenth second is silently discarded.
 * Short answers never hit this; reading a passage aloud does.
 *
 * Cutting exactly on the window boundary would slice a word in half, so each
 * cut is nudged back to the quietest short stretch inside a search zone at the
 * end of the window — the gap between words, when there is one. If the child
 * never pauses (or the room is loud enough that no stretch stands out), the
 * boundary itself is used, which is the same behaviour as not searching.
 *
 * Pure JVM: unit-tested without a device, called by the Android engine.
 */
object AudioChunker {

    /** Look this far back from a boundary for a pause, in seconds. */
    const val SEEK_BACK_SECONDS = 1.5f

    /** Width of the quiet stretch we look for, in seconds (a between-word gap). */
    const val GAP_SECONDS = 0.12f

    /**
     * @param pcm 16 kHz mono in [-1, 1].
     * @param windowSamples the export's window (melFrames * HOP).
     * @return one entry when [pcm] fits the window, else consecutive pieces
     *   that each fit, covering the input with no gaps and no overlap.
     */
    fun split(
        pcm: FloatArray,
        windowSamples: Int,
        sampleRate: Int = WhisperFrontend.SAMPLE_RATE,
    ): List<FloatArray> {
        require(windowSamples > 0) { "windowSamples must be positive" }
        if (pcm.size <= windowSamples) return listOf(pcm)

        val seekBack = (SEEK_BACK_SECONDS * sampleRate).toInt().coerceAtMost(windowSamples / 2)
        val gap = (GAP_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val pieces = ArrayList<FloatArray>()
        var start = 0
        while (start < pcm.size) {
            val remaining = pcm.size - start
            if (remaining <= windowSamples) {
                pieces.add(pcm.copyOfRange(start, pcm.size))
                break
            }
            val boundary = start + windowSamples
            val cut = quietestCut(pcm, from = boundary - seekBack, to = boundary, gap = gap)
                .coerceIn(start + gap, boundary)
            pieces.add(pcm.copyOfRange(start, cut))
            start = cut
        }
        return pieces
    }

    /**
     * Centre of the lowest-energy [gap]-wide stretch in `[from, to)`, or [to] if
     * the zone is degenerate. Uses a rolling sum of squares, so it is one pass.
     */
    private fun quietestCut(pcm: FloatArray, from: Int, to: Int, gap: Int): Int {
        val lo = from.coerceAtLeast(0)
        val hi = to.coerceAtMost(pcm.size)
        if (hi - lo <= gap) return hi

        var energy = 0.0
        for (i in lo until lo + gap) energy += pcm[i].toDouble() * pcm[i]
        var best = energy
        var bestStart = lo
        for (s in lo + 1..hi - gap) {
            val out = pcm[s - 1].toDouble()
            val into = pcm[s + gap - 1].toDouble()
            energy += into * into - out * out
            if (energy < best) {
                best = energy
                bestStart = s
            }
        }
        return bestStart + gap / 2
    }
}
