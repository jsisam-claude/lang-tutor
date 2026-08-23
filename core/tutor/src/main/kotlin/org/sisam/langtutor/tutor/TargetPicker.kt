package org.sisam.langtutor.tutor

import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.content.CurriculumUnit

/**
 * Decides WHICH lesson phrase a spoken attempt should be pronunciation-scored
 * against — by comparing the ASR transcript to every scoreable phrase in the
 * unit and taking the best word-overlap match.
 *
 * The previous behaviour was `RepeatAfterMe.firstOrNull()`: the unit's FIRST
 * phrase, regardless of what the child was asked or actually said. A child
 * answering the unit's third exercise was scored against the first one and
 * shown red marks for sounds they never attempted.
 *
 * Matching is deliberately dumb and transparent: lowercase, strip punctuation,
 * score = |shared words| / |target words|. Below [MIN_OVERLAP] nothing is
 * scored at all — free conversation must never be marked against a lesson
 * phrase it merely resembles.
 */
object TargetPicker {

    /** At least half the target's words must appear in the transcript. */
    const val MIN_OVERLAP = 0.5f

    fun pick(transcript: String, unit: CurriculumUnit?): String? {
        if (unit == null) return null
        val said = words(transcript)
        if (said.isEmpty()) return null

        val candidates = unit.activities.flatMap { activity ->
            when (activity) {
                is Activity.RepeatAfterMe -> listOf(activity.phrase)
                is Activity.Vocab -> listOf(activity.word)
                is Activity.QuestionAnswer -> activity.expectedAnswers
            }
        }
        // Rank by overlap ratio; break ties by how MANY words matched, so a
        // full-sentence attempt is scored against the full sentence, not a
        // single vocab word that also matched perfectly ("the sun is yellow"
        // must pick "The sun is yellow." over "yellow").
        return candidates
            .map { candidate ->
                val target = words(candidate)
                Scored(candidate, overlap(said, target), said.intersect(target).size)
            }
            .filter { it.ratio >= MIN_OVERLAP }
            .maxWithOrNull(compareBy({ it.ratio }, { it.matched }))
            ?.candidate
    }

    private data class Scored(val candidate: String, val ratio: Float, val matched: Int)

    private fun words(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}']+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun overlap(said: Set<String>, target: Set<String>): Float {
        if (target.isEmpty()) return 0f
        return said.intersect(target).size.toFloat() / target.size
    }
}
