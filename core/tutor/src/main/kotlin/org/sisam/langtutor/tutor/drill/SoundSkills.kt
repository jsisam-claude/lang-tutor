package org.sisam.langtutor.tutor.drill

import org.sisam.langtutor.content.TwisterSound
import org.sisam.langtutor.profile.Skill
import org.sisam.langtutor.speech.PronunciationScore

/**
 * Turns the pronunciation coach's per-phoneme scores into evidence about the
 * fifteen sounds the app actually teaches (docs/knowledge-tracing.md).
 *
 * The coach scores EVERY phone of every attempt in every room that listens,
 * which is by far the densest signal the app produces — a single spoken line
 * carries a dozen observations where a whole twister round carries one. What
 * it does not carry is meaning: /ð/ scored 0.31 is a fact about a phone, and
 * what a learner and a parent can act on is "the *th* in *this*". That
 * mapping already exists, authored in `twisters.json`, where each of the
 * fifteen target sounds names the IPA it drills; this class is only its
 * reverse index.
 *
 * Phones outside those fifteen are ignored on purpose. They are the ones
 * Hebrew already has, so a low score there is far more likely to be the
 * aligner than the learner, and recording it would bury the contrasts that
 * matter under noise.
 */
class SoundSkills(sounds: List<TwisterSound>) {

    /** IPA symbol → the sound key that claims it. */
    private val byPhone: Map<String, String> = buildMap {
        for (sound in sounds) {
            if (sound.key in UNOBSERVABLE) continue
            for (phone in sound.ipa.split(' ')) {
                val key = normalise(phone)
                if (key.isNotEmpty()) putIfAbsent(key, sound.key)
            }
        }
    }

    /** The sound key a scored phone belongs to, or null when the app does
     *  not teach it. */
    fun soundFor(symbol: String): String? = byPhone[normalise(symbol)]

    /**
     * What one scored attempt says about each sound it touched: whether MOST
     * of that sound's instances in the line came out well.
     *
     * A majority of instances rather than an average of their scores, and
     * certainly not the worst of them. One bad frame among four good ones is
     * the aligner's bad day rather than the learner's, and averaging lets
     * that one frame drag three good ones under the line — where counting
     * says what a listener would say, that the sound mostly went right.
     */
    fun observations(score: PronunciationScore, threshold: Float = GOOD): Map<String, Boolean> {
        val bySound = HashMap<String, MutableList<Float>>()
        for (phone in score.phonemes) {
            val key = soundFor(phone.symbol) ?: continue
            bySound.getOrPut(key) { mutableListOf() }.add(phone.score)
        }
        return bySound.mapKeys { Skill.sound(it.key) }
            .mapValues { (_, scores) -> scores.count { it >= threshold } * 2 >= scores.size }
    }

    /** Stress and length marks belong to the syllable, not to the sound the
     *  app teaches: ˈθ, θ and θː are all the *th* in *think*. */
    private fun normalise(symbol: String): String =
        symbol.filterNot { it in MARKS }

    companion object {
        /** The same line PronunciationFeedback draws between green and
         *  amber, so what a learner sees and what the profile records
         *  cannot drift apart. */
        const val GOOD = 0.8f

        private val MARKS = setOf('ˈ', 'ˌ', 'ː', 'ʰ', '.', ' ')

        /**
         * Sounds the coach's per-phone scores cannot speak to.
         *
         * "-ed" is realised as /t/, /d/ or /ɪd/ — the two commonest
         * consonants in the language — so a per-phone index credited the
         * skill with every "cat" and "dog" a learner said, and it read as
         * mastered after three lines. Whether "-ed" came out right is a fact
         * about the end of a past-tense word, which nothing here can see.
         *
         * "p" is the puff of air: the coach's vocabulary has pʰ, but on
         * correct audio it scores ~10 nats below plain p, so the label the
         * app asks for is p and every unaspirated p — "spin", a Hebrew
         * speaker's "pig" — reads as right. A skill that can only ever say
         * "mastered" is not a record. Both come back if the coach ever gets
         * a measure that can see them.
         */
        private val UNOBSERVABLE = setOf("ed", "p")
    }
}
