package org.sisam.langtutor.engine

import android.util.Log
import kotlin.random.Random

/**
 * Waveforms for lines the app says over and over.
 *
 * A Pixel 9 log of 2026-08-27 shows "Great job!" — four phoneme tokens —
 * taking 2977 ms to synthesize, and the vocabulary room says it after every
 * correct answer forever. The drill's fixed vocabulary (five praise lines, the
 * intro, "Almost! Listen again.", "I didn't hear you", "Good try") accounts
 * for roughly half the synthesis in that room, and every second of it is spent
 * recomputing a waveform that is byte-identical to last time. Nothing about
 * being slow at RTF 2.0 makes that a good use of a hot CPU.
 *
 * **Variants, not one recording.** A single cached rendition would make the
 * app repeat one waveform exactly, which is worse than it sounds: the ±2 %
 * pace jitter that [KokoroTtsEngine.vary] adds exists precisely because
 * identical delivery reads as robotic, and praise is the line a child hears
 * most. So each key keeps up to [MAX_VARIANTS] renditions — the first few
 * utterances synthesize and are kept, and after that the line is instant AND
 * still varies.
 *
 * Deliberately bounded and deliberately narrow: only SHORT lines are eligible,
 * so a conversation reply (long, never repeated) cannot evict the praise that
 * actually repeats. Capacity is counted in audio samples rather than entries,
 * because that is what the memory cost actually is.
 */
object SynthCache {

    private const val TAG = "TukiTts"

    /** Long lines never repeat; caching them is pure eviction pressure. */
    const val MAX_TEXT_CHARS = 64

    /** Enough that repetition is not audible; small enough to stay cheap. */
    const val MAX_VARIANTS = 3

    /** ~60 s of 24 kHz mono float audio, about 5.8 MB. */
    const val MAX_SAMPLES = 60 * 24_000

    private val entries = LinkedHashMap<String, MutableList<FloatArray>>()
    private var samples = 0

    fun eligible(text: String): Boolean = text.length <= MAX_TEXT_CHARS

    /**
     * A cached rendition, or null when this key still needs more variants.
     * Returning null is the signal to synthesize and then [put] the result.
     */
    @Synchronized
    fun get(key: String, random: Random = Random.Default): FloatArray? {
        val variants = entries[key] ?: return null
        if (variants.size < MAX_VARIANTS) return null
        // Touch for LRU: re-inserting moves it to the end of the access order.
        entries.remove(key)
        entries[key] = variants
        return variants[random.nextInt(variants.size)]
    }

    @Synchronized
    fun put(key: String, audio: FloatArray) {
        if (audio.isEmpty() || audio.size > MAX_SAMPLES) return
        val variants = entries.getOrPut(key) { mutableListOf() }
        if (variants.size >= MAX_VARIANTS) return
        variants += audio
        samples += audio.size
        evictWhileOver()
    }

    /** Oldest keys first — LinkedHashMap keeps insertion/access order. */
    private fun evictWhileOver() {
        while (samples > MAX_SAMPLES && entries.isNotEmpty()) {
            val oldest = entries.keys.first()
            entries.remove(oldest)?.forEach { samples -= it.size }
        }
    }

    /** Dropped with the ORT session under memory pressure — the audio is
     *  worth far less than the model it sits next to. */
    @Synchronized
    fun clear() {
        if (entries.isEmpty()) return
        Log.i(TAG, "synth cache cleared (${entries.size} lines, ${samples / 24_000}s)")
        entries.clear()
        samples = 0
    }

    /**
     * Key must separate everything that changes the waveform — including
     * [speed], because the drill's "Almost! Listen again." recast is the same
     * sentence at 0.75x and must not be served the full-speed rendition.
     */
    fun key(text: String, voice: String, pitch: Float?, speed: Float): String =
        "$voice|${pitch ?: 0f}|${"%.2f".format(speed)}|$text"
}
