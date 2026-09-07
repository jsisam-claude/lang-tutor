package org.sisam.langtutor.tutor.drill

import org.sisam.langtutor.speech.KokoroPhonemizer

/**
 * How a written word SOUNDS, for the judge.
 *
 * The judge compares the target line with what the recogniser wrote down,
 * and the recogniser is free to spell. Measured over every short item in
 * the bank, synthesised and run through the shipped Whisper: "Are you OK?"
 * came back "Are you okay?" in twelve of twelve voice-and-speed cells, "I
 * see cereal." came back "IC serial.", and a learner who said each of them
 * perfectly was told "Almost! Listen again." three times and then failed.
 * Spelling is not what the room is judging.
 *
 * A [key] is a string two words share exactly when they are said the same
 * way — stress and syllable marks stripped, because "okay" and "OK" differ
 * in nothing a listener can hear. It is exact equality, not a distance:
 * the run that found this also measured a phonetic edit distance as the
 * fallback, and at any slack that rescued "OK" it also passed "three ship"
 * for "three sheep" — most of the fifteen contrasts the twisters room
 * exists to teach. Slack is zero and stays zero.
 */
fun interface Pronunciation {

    /** The sound of one written word, or null when nothing is known. */
    fun key(word: String): String?

    companion object {
        /** Spelling only — what the judge was before it could hear. */
        val NONE: Pronunciation = Pronunciation { null }

        /** The app's own front end, the same one that voices the line. */
        fun of(phonemizer: KokoroPhonemizer): Pronunciation = Pronunciation { word ->
            // "dogs'" is said exactly as "dogs"; the apostrophe is spelling.
            // And a word the front end cannot voice is a word with no key,
            // never a throw: this runs on the early-close path, on whatever
            // the recogniser wrote.
            runCatching { phonemizer.phonemizeToIpa(word.trimEnd('\'')) }.getOrNull()
                ?.filterNot { it in MARKS }?.ifBlank { null }
        }

        /**
         * Loaded on first use. The dictionary is 140,000 lines and the drill
         * room opens before anyone has said anything, so the load is paid on
         * the first verdict rather than on the way in.
         */
        fun lazy(load: () -> KokoroPhonemizer): Pronunciation {
            val inner by kotlin.lazy { of(load()) }
            return Pronunciation { word -> inner.key(word) }
        }

        /** Stress belongs to the syllable, not to whether two words are the same word. */
        private val MARKS = setOf('ˈ', 'ˌ', ' ')
    }
}
